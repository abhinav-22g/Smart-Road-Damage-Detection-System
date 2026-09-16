package com.example.smartroaddamage.data

/**
 * Central registry for the active hazard read-side implementation.
 *
 * This keeps the mock/live switch in one place instead of hard-coding it in
 * ViewModels. The production default is the canonical Firestore `potholes`
 * collection.
 */
object HazardReadRepositories {
    val active: HazardReadRepository = FirestoreHazardReadRepository()
}
