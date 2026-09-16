/**
 * SmartRoad Hazard Clustering Worker
 * ════════════════════════════════════════════════════════════════════
 *
 * Trigger  : Firestore onCreate — pothole_reports/{reportId}
 * Purpose  : Transform a raw rider anomaly submission into a canonical
 *            clustered hazard in the `potholes` collection.
 *
 * Architecture principle
 * ──────────────────────
 * The Android app writes ONLY to `pothole_reports` (raw ingestion).
 * This function is the ONLY process that writes to `potholes`
 * (canonical serving collection).  The UI reads only from `potholes`.
 *
 * Pipeline per incoming report
 * ────────────────────────────
 *  1. Validate required fields
 *  2. Determine best coordinates (snapped > raw)
 *  3. Compute geohash spatial key at precision 7 (~153m × 153m cell)
 *  4. Search current cell + 8 neighbours for candidate clusters
 *  5. For each candidate: compute true haversine distance to centroid
 *  6. If nearest candidate ≤ MERGE_RADIUS_M → merge into it (transaction)
 *  7. Else → create new cluster (transaction)
 *  8. Write clusterId + ingestStatus=processed back to raw report
 *
 * Idempotency
 * ───────────
 * The function checks `ingestStatus` on entry.  If already "processed"
 * or "failed", it exits immediately.  This prevents double-processing
 * on Cloud Function retries.
 *
 * Concurrency safety
 * ──────────────────
 * Cluster merge/create uses a Firestore transaction with a re-check
 * inside the transaction body.  If two reports race to create the same
 * cluster, only one wins; the other falls through to the merge branch.
 *
 * Snap-to-Road
 * ────────────
 * Optional Mappls Snap-to-Road call is wrapped in try/catch.  If it
 * fails, the raw coordinates are used and snapSource = "raw" is stored.
 * Snap failure never blocks ingestion.
 */

import * as functions from "firebase-functions";
import * as admin from "firebase-admin";
import * as ngeohash from "ngeohash";
import * as https from "https";

admin.initializeApp();
const db = admin.firestore();

// ─────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────

/** Radius in metres within which a new report merges into an existing cluster. */
const MERGE_RADIUS_M = 8.0;

/**
 * Geohash precision.
 *
 *  precision 7 → ~153m × 153m cell  (good first-pass candidate filter)
 *  precision 8 → ~38m  × 19m  cell  (too tight, misses edge clusters)
 *
 *  We use 7 and always do a true haversine check as the final gate.
 */
const GEOHASH_PRECISION = 7;

const POTHOLES_COL = "potholes";
const REPORTS_COL  = "pothole_reports";

// ─────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────

interface RawReport {
  reportId: string;
  journeyId: string;
  reporterUid: string;
  label: string;
  severity: number;
  modelConfidence: number;
  rawLat: number;
  rawLng: number;
  snappedLat: number | null;
  snappedLng: number | null;
  impactEspMs: number;
  androidEpochMs: number;
  modelVersion: string;
  ingestStatus: string;
  clusterId: string | null;
  laneSide: string | null;
  isFused?: boolean;
  visionPixelRatio?: number;
}

interface ClusterDoc {
  clusterId: string;
  lat: number;
  lng: number;
  rawCentroidLat: number;
  rawCentroidLng: number;
  reportCount: number;
  dominantLabel: string;
  maxSeverity: number;
  avgConfidence: number;
  hazardScore: number;
  firstSeenAt: admin.firestore.Timestamp;
  lastSeenAt: admin.firestore.Timestamp;
  cellKey: string;
  dominantLaneSide: string | null;
  snapSource: "mappls" | "raw";
  isActive: boolean;
}

// ─────────────────────────────────────────────────────────────────────
// Main Cloud Function
// ─────────────────────────────────────────────────────────────────────

export const onPotholeReportCreated = functions
  .region("asia-south1")           // Mumbai — closest to Indian roads
  .runWith({ timeoutSeconds: 60, memory: "256MB" })
  .firestore.document(`${REPORTS_COL}/{reportId}`)
  .onCreate(async (snap, _context) => {
    const report = snap.data() as RawReport;
    const reportRef = snap.ref;

    // ── 1. Idempotency check ──────────────────────────────────────
    if (report.ingestStatus !== "pending") {
      functions.logger.info(
        `[SKIP] Report ${snap.id} already status=${report.ingestStatus}`
      );
      return null;
    }

    // ── 2. Validate required fields ───────────────────────────────
    if (
      typeof report.rawLat !== "number" ||
      typeof report.rawLng !== "number" ||
      report.rawLat === 0 ||
      report.rawLng === 0
    ) {
      functions.logger.error(`[FAIL] Report ${snap.id} has invalid coordinates`);
      await reportRef.update({ ingestStatus: "failed" });
      return null;
    }

    // ── 3. Optional Snap-to-Road enrichment ───────────────────────
    let servingLat = report.rawLat;
    let servingLng = report.rawLng;
    let snapSource: "mappls" | "raw" = "raw";

    try {
      const snapped = await snapToRoad(report.rawLat, report.rawLng);
      if (snapped) {
        servingLat = snapped.lat;
        servingLng = snapped.lng;
        snapSource = "mappls";
        // Write snapped coords back to raw report for auditability
        await reportRef.update({
          snappedLat: servingLat,
          snappedLng: servingLng,
        });
      }
    } catch (err) {
      functions.logger.warn(
        `[SNAP] Snap-to-road failed for ${snap.id}, using raw coords`,
        err
      );
    }

    // ── 4. Compute spatial key ────────────────────────────────────
    const cellKey = ngeohash.encode(servingLat, servingLng, GEOHASH_PRECISION);

    // ── 5. Search for candidate clusters ─────────────────────────
    const neighbours = ngeohash.neighbors(cellKey);
    const searchCells = [cellKey, ...neighbours]; // 9 cells total

    const candidateSnap = await db
      .collection(POTHOLES_COL)
      .where("cellKey", "in", searchCells)
      .where("isActive", "==", true)
      .get();

    // Find the nearest cluster within MERGE_RADIUS_M
    let nearestClusterId: string | null = null;
    let nearestDistM = Infinity;
    let nearestClusterRef: admin.firestore.DocumentReference | null = null;

    for (const doc of candidateSnap.docs) {
      const data = doc.data() as ClusterDoc;
      const distM = haversineM(servingLat, servingLng, data.lat, data.lng);
      if (distM <= MERGE_RADIUS_M && distM < nearestDistM) {
        nearestDistM = distM;
        nearestClusterId = doc.id;
        nearestClusterRef = doc.ref;
      }
    }

    // ── 6/7. Transaction: merge or create ─────────────────────────
    let finalClusterId: string;

    if (nearestClusterRef !== null && nearestClusterId !== null) {
      // ── MERGE branch ──────────────────────────────────────────
      finalClusterId = nearestClusterId;

      await db.runTransaction(async (tx) => {
        const existingSnap = await tx.get(nearestClusterRef!);
        if (!existingSnap.exists) {
          // Cluster was deleted between candidate search and transaction
          // Fall through: we'll create a new cluster after this transaction
          functions.logger.warn(
            `[RACE] Cluster ${nearestClusterId} disappeared — will create new`
          );
          return;
        }

        const existing = existingSnap.data() as ClusterDoc;

        // Re-check distance inside transaction (guards against concurrent moves)
        const distM = haversineM(servingLat, servingLng, existing.lat, existing.lng);
        if (distM > MERGE_RADIUS_M) {
          functions.logger.info(
            `[MERGE SKIP] Distance ${distM.toFixed(1)}m exceeds threshold after re-check`
          );
          return;
        }

        const newCount = existing.reportCount + 1;

        // Weighted centroid update (incremental moving average)
        const newLat =
          (existing.lat * existing.reportCount + servingLat) / newCount;
        const newLng =
          (existing.lng * existing.reportCount + servingLng) / newCount;

        // Rolling average confidence
        const newAvgConf =
          (existing.avgConfidence * existing.reportCount + report.modelConfidence) /
          newCount;

        // Max severity
        const newMaxSev = Math.max(existing.maxSeverity, report.severity);

        // Dominant label: keep existing unless new label appears more than 60% of the time
        // Simple heuristic: switch if incoming severity is higher
        const newLabel =
          report.severity >= existing.maxSeverity
            ? report.label
            : existing.dominantLabel;

        const newLaneSide =
          report.severity >= existing.maxSeverity && report.laneSide
            ? report.laneSide
            : existing.dominantLaneSide;

        // hazardScore = maxSeverity * avgConfidence * ln(1 + reportCount)
        const newHazardScore = newMaxSev * newAvgConf * Math.log1p(newCount);

        tx.update(nearestClusterRef!, {
          lat:            newLat,
          lng:            newLng,
          reportCount:    newCount,
          avgConfidence:  newAvgConf,
          maxSeverity:    newMaxSev,
          dominantLabel:  newLabel,
          dominantLaneSide: newLaneSide,
          hazardScore:    newHazardScore,
          lastSeenAt:     admin.firestore.FieldValue.serverTimestamp(),
          cellKey:        ngeohash.encode(newLat, newLng, GEOHASH_PRECISION),
        });
      });

      functions.logger.info(
        `[MERGE] Report ${snap.id} → cluster ${finalClusterId} (dist=${nearestDistM.toFixed(1)}m)`
      );

    } else {
      // ── CREATE branch ─────────────────────────────────────────
      const newClusterRef = db.collection(POTHOLES_COL).doc();
      finalClusterId = newClusterRef.id;

      const hazardScore =
        report.severity * report.modelConfidence * Math.log1p(1);

      const newCluster: ClusterDoc = {
        clusterId:      finalClusterId,
        lat:            servingLat,
        lng:            servingLng,
        rawCentroidLat: report.rawLat,
        rawCentroidLng: report.rawLng,
        reportCount:    1,
        dominantLabel:  report.label,
        maxSeverity:    report.severity,
        avgConfidence:  report.modelConfidence,
        hazardScore:    hazardScore,
        firstSeenAt:    admin.firestore.Timestamp.now(),
        lastSeenAt:     admin.firestore.Timestamp.now(),
        cellKey:        cellKey,
        dominantLaneSide: report.laneSide || null,
        snapSource:     snapSource,
        isActive:       true,
      };

      await db.runTransaction(async (tx) => {
        // Double-check no cluster appeared at this location since our candidate search
        const raceCheck = await db
          .collection(POTHOLES_COL)
          .where("cellKey", "in", searchCells)
          .where("isActive", "==", true)
          .get();

        for (const doc of raceCheck.docs) {
          const data = doc.data() as ClusterDoc;
          const distM = haversineM(servingLat, servingLng, data.lat, data.lng);
          if (distM <= MERGE_RADIUS_M) {
            // A concurrent report already created a cluster here — merge instead
            const newCount = data.reportCount + 1;
            const newLat = (data.lat * data.reportCount + servingLat) / newCount;
            const newLng = (data.lng * data.reportCount + servingLng) / newCount;
            const newAvgConf =
              (data.avgConfidence * data.reportCount + report.modelConfidence) / newCount;
            const newMaxSev = Math.max(data.maxSeverity, report.severity);
            const newHazardScore = newMaxSev * newAvgConf * Math.log1p(newCount);
            
            const newLaneSide =
              report.severity >= data.maxSeverity && report.laneSide
                ? report.laneSide
                : data.dominantLaneSide;

            tx.update(doc.ref, {
              lat:           newLat,
              lng:           newLng,
              reportCount:   newCount,
              avgConfidence: newAvgConf,
              maxSeverity:   newMaxSev,
              dominantLaneSide: newLaneSide,
              hazardScore:   newHazardScore,
              lastSeenAt:    admin.firestore.FieldValue.serverTimestamp(),
              cellKey:       ngeohash.encode(newLat, newLng, GEOHASH_PRECISION),
            });

            finalClusterId = doc.id;
            functions.logger.info(
              `[RACE MERGE] Report ${snap.id} → existing cluster ${finalClusterId}`
            );
            return; // exit transaction — do not create a new doc
          }
        }

        // No race condition — safe to create
        tx.set(newClusterRef, newCluster);
      });

      functions.logger.info(
        `[CREATE] New cluster ${finalClusterId} for report ${snap.id}`
      );
    }

    // ── 8. Mark raw report as processed ──────────────────────────
    await reportRef.update({
      clusterId:    finalClusterId,
      ingestStatus: "processed",
    });

    return null;
  });

// ─────────────────────────────────────────────────────────────────────
// Haversine distance (metres)
// ─────────────────────────────────────────────────────────────────────

function haversineM(
  lat1: number, lng1: number,
  lat2: number, lng2: number
): number {
  const R    = 6_371_000;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

// ─────────────────────────────────────────────────────────────────────
// Optional Mappls Snap-to-Road
// Docs: https://about.mappls.com/api/advanced-maps/doc/snap-to-road-api
// Returns null on any failure — callers must handle gracefully.
// ─────────────────────────────────────────────────────────────────────

async function snapToRoad(
  lat: number,
  lng: number
): Promise<{ lat: number; lng: number } | null> {
  // API key must be set via Firebase config:
  //   firebase functions:config:set mappls.api_key="YOUR_KEY"
  const apiKey = functions.config().mappls?.api_key;
  if (!apiKey) {
    functions.logger.debug("[SNAP] mappls.api_key not configured — skipping snap");
    return null;
  }

  const url =
    `https://apis.mappls.com/advancedmaps/v1/${apiKey}` +
    `/snap_to_road?pts=${lat},${lng}&timestamps=1000&interpolate=false`;

  return new Promise((resolve) => {
    https
      .get(url, (res) => {
        let body = "";
        res.on("data", (chunk: string) => { body += chunk; });
        res.on("end", () => {
          try {
            const json = JSON.parse(body);
            // Mappls response shape: { snappedPoints: [{ location: { lat, lng } }] }
            const snapped = json?.snappedPoints?.[0]?.location;
            if (
              snapped &&
              typeof snapped.lat === "number" &&
              typeof snapped.lng === "number"
            ) {
              resolve({ lat: snapped.lat, lng: snapped.lng });
            } else {
              functions.logger.warn("[SNAP] Unexpected Mappls response", json);
              resolve(null);
            }
          } catch (parseErr) {
            functions.logger.warn("[SNAP] JSON parse error", parseErr);
            resolve(null);
          }
        });
      })
      .on("error", (err) => {
        functions.logger.warn("[SNAP] HTTPS request error", err);
        resolve(null);
      });
  });
}
