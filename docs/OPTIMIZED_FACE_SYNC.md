# Optimized Gateway <-> Spring Boot Face Sync

## Problem found

The reader contains about 1,196 users. The old path performed one TrueFace SDK `GetFace(userId)` call per user and one HTTP upload per photo.

Observed behavior from the supplied logs/source:
- `GetFace` is about 5 seconds per call.
- 1,196 individual reads can therefore approach 100 minutes.
- the gateway already had a durable report queue, but network delivery could still block the device-sync path.
- REST polling could repeatedly hit the backend while DNS/WSS was unhealthy.

## New flow

1. `RECONCILE_DEVICE` reads the trusted roster and attendance.
2. Gateway stores the roster locally and starts the face import independently.
3. `TrueFaceDeviceAdapter.GetFaces()` sends up to 20 user IDs in one native SDK call.
   The SDK contract in the source allows up to 100 IDs; 20 is deliberately conservative because each
   returned photo has a native buffer.
4. Successful photos are cached on disk by SHA-256.
5. Gateway creates `DEVICE_USER_CHANGED` reports as before, preserving the existing latest-change-wins
   and idempotency behavior.
6. Up to 20 cached photos are uploaded to Spring Boot in one `multipart/form-data` request.
7. Spring Boot normalizes/stores all images in one transaction and returns one `uploadId` per image.
8. Gateway publishes the existing `DEVICE_USER_CHANGED` messages, each referencing its `faceUploadId`.
9. Existing `DeviceUserChangeService` consumes those upload IDs and applies the existing business rules.
10. If backend connectivity fails, reports remain durable and the gateway backs off rather than blocking
    reader operations.

## Why 1,196 photos now finish much faster

Old:
- approximately 1,196 native face calls
- approximately 1,196 HTTP uploads

New:
- approximately 60 native face calls at 20 users/call
- approximately 60 HTTP batch uploads at 20 photos/request

The actual time depends on reader firmware and image size, but the number of network/native round trips is reduced by roughly 20x.

## Safety

The implementation deliberately does not infer deletions from incomplete user lists.
A batch face response that omits a requested user is treated as a read failure, not as "no photo".
Successful users in the same SDK batch are still recorded even if another user in that batch fails.

## Backend endpoint

`POST /internal/gateway/faces/batch`

Multipart parts:
- `metadata`: JSON array aligned with `faces`
- repeated `faces`: JPEG files

Example metadata:

```json
[
  {"deviceUserId":"1001","sha256":"..."},
  {"deviceUserId":"1002","sha256":"..."}
]
```

Response:

```json
{
  "count": 2,
  "items": [
    {"index":0,"deviceUserId":"1001","uploadId":"...","sha256":"...","sizeBytes":12345},
    {"index":1,"deviceUserId":"1002","uploadId":"...","sha256":"...","sizeBytes":23456}
  ]
}
```

The upload IDs remain individual because the existing `DEVICE_USER_CHANGED` protocol references one face upload at a time.

## Configuration

The gateway full roster poll default is now 300 seconds (5 minutes). Device events still trigger targeted scans.
Face sweep remains 30 minutes by default.

The backend multipart request limit is 11 MB and the gateway batch is 20 images, comfortably below that
limit for the reader's documented face-photo size.
