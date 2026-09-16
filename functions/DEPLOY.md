# Deploying the SmartRoad Hazard Pipeline Backend

## Prerequisites

```bash
npm install -g firebase-tools
firebase login
firebase use --add   # select your Firebase project
```

## 1. Install function dependencies

```bash
cd functions
npm install
```

## 2. Set Mappls API key (optional — enables snap-to-road enrichment)

```bash
firebase functions:config:set mappls.api_key="YOUR_MAPPLS_REST_API_KEY"
```

> If you skip this step, snap-to-road is automatically skipped and raw
> coordinates are used. Ingestion still works normally.

## 3. Build TypeScript

```bash
npm run build
```

## 4. Test locally with the emulator

```bash
cd ..   # back to project root
firebase emulators:start --only functions,firestore
```

Point the Android app at the local emulator by adding this to
`SmartRoadApplication.kt` before `FirebaseApp.initializeApp`:

```kotlin
// EMULATOR ONLY — remove before production build
val firestore = FirebaseFirestore.getInstance()
firestore.useEmulator("10.0.2.2", 8080)
```

## 5. Deploy to production

```bash
firebase deploy --only functions,firestore
```

This deploys:
- `onPotholeReportCreated` Cloud Function (asia-south1 region)
- Firestore security rules (`firestore.rules`)
- Firestore composite indexes (`firestore.indexes.json`)

## 6. Switch Android app from mock to live reads

In `RouteSelectionViewModel.kt`, change:

```kotlin
// Before (mock)
private val routeRepository = RouteRepository(
    hazardReadRepository = MockHazardReadRepository
)

// After (live)
private val routeRepository = RouteRepository(
    hazardReadRepository = FirestoreHazardReadRepository()
)
```

That is the only code change needed to go fully live.

## Verifying the pipeline end-to-end

1. Drive a session, detect anomalies, tap **UPLOAD DATA**.
2. In Firebase Console → Firestore → `pothole_reports`, verify docs appear
   with `ingestStatus = "pending"`.
3. After ~5 seconds, verify `ingestStatus` changes to `"processed"` and
   `clusterId` is populated.
4. In `potholes` collection, verify a new cluster doc appeared (or an
   existing one has an incremented `reportCount`).
5. On next route scan, the canonical cluster will appear in route analysis
   and on the map.

## Monitoring

```bash
firebase functions:log --only onPotholeReportCreated
```

Look for:
- `[CREATE]` — new cluster created
- `[MERGE]`  — report merged into existing cluster
- `[SNAP]`   — snap-to-road result
- `[FAIL]`   — bad coordinates, check Android GPS fusion
