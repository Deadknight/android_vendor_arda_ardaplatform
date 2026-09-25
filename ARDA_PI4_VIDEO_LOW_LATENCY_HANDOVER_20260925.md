# ARDA Pi4 video low-latency closeout — 2026-09-25

## Preferred Pi4 production path

```text
hardware profile: pi4_hdmi
vendor.hwc.drm.enable_virtual_display=0
video route: HDMI / Direct PCT
codec: c2.v4l2.avc.encoder
resolution: 1280x720
fps: 30
baseline bitrate: 2.5 Mbps
```

This path produced the best observed Maps motion/input latency in the final
A/B tests. Subjective latency was reported as effectively negligible.

Legacy HDMI run:

```text
VIDEO_SENT == VIDEO_ACK
MAX_UNACKED=2
real encoded rate ~=28.81 fps
PTS/wall ~=0.9598
```

Perceived motion-to-photon/input latency is the primary Pi4 production criterion.

## Preserved fallbacks

Do not remove:

```text
HWC-VDS + HDMI Direct PCT + V4L2 720p30
  ~=30.08 fps
  PTS/wall ~=1.002
  low latency
  cadence/performance fallback

HWC-VDS + TXP + V4L2 720p30
  ~=30.34 fps
  PTS/wall ~=1.011
  transport-clean, but subjective latency varied
  fallback
```

## Rejected Pi4 production targets

```text
HWC-VDS + V4L2 requested 720p60
  actual ~=32.48 fps
  PTS/wall ~=0.541
  subjective delay ~=1.5 s

HWC-VDS + c2.android.avc.encoder 720p30
  actual ~=27.98 fps
  PTS/wall ~=0.926
  subjective delay ~=1.5 s with accumulation
```

## Tuning policy

UI settings stay visible. Backend uses only controls applicable to the active codec/path.

```text
Bitrate:
  codec-independent
  arda_video_bitrate_bps > 0
    -> AAP --video-bit-rate
    -> screenrecord --bit-rate
    -> selected encoder
  0 -> AAP production/default bitrate

GPU conversion:
  software conversion path only
  Pi4 legacy V4L2 ignores/defaults it

Encoder speed / IVE:
  software C2SoftAvcEnc only
  Pi4 V4L2 ignores it
```

Read-only V4L2 audit:

```text
external/v4l2_codec2
HEAD=99f85153d805c6ed7aa09fab869a965ce4c65b43
```

The V4L2 encoder exposes bitrate/bitrate-mode, B-frame, GOP/I-period, QP,
profile/level and key-frame controls, but no generic C2 speed/low-latency
control and no IVE enum. Do not map IVE presets speculatively to GOP/B-frame controls.

## Closure

Pi4 video-path investigation is closed at this checkpoint unless a regression appears.
Keep HDMI legacy, HWC-VDS and TXP code paths intact.
