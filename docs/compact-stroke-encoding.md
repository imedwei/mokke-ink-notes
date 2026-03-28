# Compact Stroke Encoding Design

## Problem

`.inkup` files are 100KB–1MB due to per-point sub-message overhead. Each `StrokePointProto` costs ~28 bytes (4 tagged float/int64 fields + sub-message framing). A 500-point stroke is ~14KB.

## Industry Survey

All major ink platforms converge on the same encoding strategy:

| Platform | Layout | Compression | Reference |
|----------|--------|-------------|-----------|
| Microsoft ISF | Column (per-channel) | Delta → delta-of-delta → zigzag → Huffman | [ISF Spec](https://learn.microsoft.com/en-us/uwp/specifications/ink-serialized-format) |
| Wacom WILL | Column (per-channel) | Quantize → delta → protobuf varint | [WILL Encoding](https://developer-docs.wacom.com/docs/sdk-for-ink/uim/encoding/) |
| Google Ink | Column (`CodedNumericRun`) | Quantize → delta → sint32 packed varint | [ink/storage/proto](https://github.com/google/ink/tree/main/ink/storage/proto) |
| Apple PencilKit | B-spline control points | Inherently reduced point count | [PKStrokePath](https://developer.apple.com/documentation/pencilkit/pkstrokepath-swift.struct) |
| W3C InkML | Text, per-channel | First/second difference notation | [InkML Spec](https://www.w3.org/TR/InkML/) |

### Common pattern

1. **Struct-of-arrays** — separate packed arrays for X, Y, pressure, time (not per-point sub-messages)
2. **Quantize** floats to integers with a scale factor
3. **Delta encode** consecutive values
4. **Packed zigzag varints** for the deltas

### Google Ink's `CodedNumericRun` (cleanest protobuf reference)

```protobuf
message CodedNumericRun {
  repeated sint32 deltas = 1 [packed = true];
  optional float scale = 2 [default = 1];
  optional float offset = 3;
}
```

Reconstruction: `val[0] = offset + scale * deltas[0]`, then `val[i] = val[i-1] + scale * deltas[i]`.

## Precision Requirements

| Channel | Needed precision | Rationale |
|---------|-----------------|-----------|
| Coordinates | 0.01 line-units (0.5px at 50px spacing) | Visually indistinguishable; recognition works with integers |
| Pressure | 256–1024 levels | Hardware reports 1024–8192 but >4096 is imperceptible; academic datasets use 8-bit |
| Timestamps | 1ms deltas | ~5ms intervals at 200Hz → 1-byte varints |
| Tilt/Azimuth | Not stored | Not captured on current devices |

## Proposed Schema

Add `NumericRunProto` (matches Google Ink's `CodedNumericRun`) and use column-oriented stroke encoding. Each stroke is self-contained (has its own offset) for independent random-access decoding.

```protobuf
// Compact per-channel delta-encoded numeric sequence.
// Decode: val[0] = offset + scale * deltas[0]
//         val[i] = val[i-1] + scale * deltas[i]
message NumericRunProto {
    repeated sint32 deltas = 1 [packed = true];
    optional float scale = 2 [default = 1];
    optional float offset = 3 [default = 0];
}
```

New fields on `InkStrokeProto` (fields 6+, alongside existing v1 fields for backward compat):

```protobuf
message InkStrokeProto {
    // Existing v1 fields (kept for backward compatibility)
    optional string stroke_id = 1 [default = ""];
    optional float stroke_width = 2 [default = 3];
    repeated StrokePointProto points = 3;
    optional StrokeTypeProto stroke_type = 4 [default = FREEHAND];
    optional bool is_geometric = 5 [default = false];

    // Compact column-oriented encoding (v3).
    // When x_run is present, decode from runs; 'points' field is empty.
    // All runs must have the same logical length (number of points).
    optional NumericRunProto x_run = 6;
    optional NumericRunProto y_run = 7;
    optional NumericRunProto pressure_run = 8;   // omitted if all pressures are 1.0
    optional NumericRunProto time_run = 9;       // omitted if timestamps not needed
}
```

### Encoding parameters

| Channel | scale | offset | Notes |
|---------|-------|--------|-------|
| x | 0.01 | first_x (normalized) | 0.01 line-unit precision |
| y | 0.01 | first_y (normalized) | 0.01 line-unit precision |
| pressure | 0.01 | 0 | 100 levels in [0, 1]; omit run entirely if all 1.0 |
| time | 1.0 | first_timestamp_ms | Millisecond deltas; omit if no timestamps |

### Random access

Each stroke is independently decodable — the `offset` field provides the absolute starting value. No dependency on preceding strokes.

## Point Reduction: Douglas-Peucker

Applied to **freehand strokes only** before encoding (geometric strokes are already minimal).

- Tolerance: 0.02 line-units (1px at 50px line spacing)
- Typical reduction: ~50% of points removed
- Preserves first and last point; preserves corners
- Iterative (stack-based) implementation avoids stack overflow

## Expected Size

| Encoding | Bytes/point | 500-point stroke | 100-stroke doc |
|----------|-------------|-----------------|----------------|
| v1 (current) | ~28 | ~14,000 | ~1.4 MB |
| v3 column-oriented | ~4 | ~2,000 | ~200 KB |
| v3 + Douglas-Peucker | ~4 × 0.5 | ~1,000 | ~100 KB |

**5–10x smaller** lossless; **10–15x** with DP simplification.

## Backward Compatibility

- Old reader encountering v3: sees empty `points` field, unknown fields 6-9 go to `unknownFields`. Stroke appears empty. Acceptable: the app version that writes v3 also reads v3.
- New reader encountering v1: `x_run` is null → falls back to reading `points` field (existing path).
- No existing golden files are modified.

## References

- [Google Ink CodedNumericRun](https://github.com/google/ink/blob/main/ink/storage/proto/coded_numeric_run.proto)
- [ISF Specification](https://learn.microsoft.com/en-us/uwp/specifications/ink-serialized-format)
- [Wacom WILL Encoding](https://developer-docs.wacom.com/docs/sdk-for-ink/uim/encoding/)
- [Protobuf Encoding Guide](https://protobuf.dev/programming-guides/encoding/)
- [Gorilla (VLDB 2015)](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf) — XOR encoding for float time series
- [Ramer-Douglas-Peucker](https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm)
