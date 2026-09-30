# ADR-0010: Normalize Covers and Document Representatives

- Status: Accepted
- Date: 2026-09-30
- Decider: Yurlib project owner

## Context

Embedded images and rendered pages are hostile derived content. Serving them directly trusts declared type, dimensions, codecs, metadata, and active payloads. PDF, DOCX, and DjVu also lack one universal cover convention.

## Decision

Select covers in this order: curator override, declared embedded cover, safe DOCX package thumbnail, bounded PDF/DjVu page-one render, then generic fallback. Do not select arbitrary first images.

Probe before decode, enforce encoded/pixel/allocation/deadline limits, strip metadata, normalize orientation, and publish an immutable derivative no larger than 1600×2400 and 8 megapixels. Prefer WebP, using JPEG/PNG only for required compatibility or transparency. Retain source locator/page, hashes, processor/version, dimensions, limits, and root lineage. Cover failure never fails catalog ingestion.

## Consequences

### Positive

- Browsers never receive untrusted source image bytes as trusted covers.
- Every cover is reproducible, cacheable, attributable, and authorization-aware.
- Documents receive useful representatives with safe fallback.

### Negative

- Rendering and normalization consume managed storage and background capacity.
- Animated or unusual source image features are intentionally discarded.

## Alternatives considered

### Serve embedded bytes directly

Rejected because it bypasses media validation and normalization.

### Never render document pages

Rejected because the owner approved bounded first-page representatives for PDF and DjVu.

## Supersession

None.
