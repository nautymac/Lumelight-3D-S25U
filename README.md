<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" alt="Lumelight 3D S25U">
</p>

<h1 align="center">Lumelight 3D — S25 Ultra</h1>

<p align="center">
  Moonlight-based glasses-free 3D game streaming for the Galaxy S25 Ultra with a SpatialGlass 3D film<br>
  갤럭시 S25 Ultra + SpatialGlass 3D 필름용 무안경 3D 게임 스트리밍
</p>

<p align="center">
  <a href="#한국어">한국어</a> · <a href="#english">English</a>
</p>

---

## 한국어

[Artemis](https://github.com/ClassicOldSong/moonlight-android)(Moonlight Android 포크)를 바탕으로 한 [Lumelight 3D](https://github.com/nautymac/Lumelight-3D)의 **갤럭시 S25 Ultra + SpatialGlass 3D 필름** 버전입니다. PC 게임을 스트리밍하면서 폰에서 안경 없이 3D로 봅니다.

Leia 태블릿과 달리 이 필름은 **렌즈일 뿐**이라 기기에 3D 엔진이 없습니다. 그래서 이 앱이 직접 합니다.

- **위빙**: 두 눈 그림을 화소마다 필름의 렌즈에 맞춰 섞습니다. 색(빨강·초록·파랑)마다 따로 측정한 섞기 곡선을 씁니다.
- **눈 추적**: 앞카메라로 두 눈의 홍채를 초당 약 60번 찾아, 머리를 움직여도 3D가 유지되게 위빙을 옮깁니다. 기기 안에서만 처리하고 영상은 어디에도 보내지 않습니다.

### 기능

| 기능 | 설명 |
|---|---|
| **PC가 보내는 SBS 그대로 3D** | 3D Source = *좌우 분할 (PC가 3D로 보냄)*. Geo-11, 3D Vision, SBS 영상 등을 필름에 맞춰 섞습니다. |
| **2D 게임을 3D로** | 3D Source = *2D → 3D (AI)*. 폰의 깊이 모델로 3D를 만듭니다. |
| **세 손가락 1초** | 언제든 SBS 그대로 ↔ 필름 3D 전환. 터치 설정과 상관없이 동작합니다. |
| **3D 조절 바** | 3D 중 오른쪽 위 **3D 조절** 버튼 → 위상·추적 세기·밝기·대비를 보면서 맞춥니다. 값은 저장됩니다. |
| **밝기·대비 보정** | 필름 3D는 각 눈에 화소 일부만 가서 어둡습니다. 기본으로 밝기 1.4, 대비 1.15로 올립니다(조절 가능). |
| **16:9 정중앙** | 1080p 등 어떤 해상도도 화면 정중앙에 같은 여백으로 놓고, 화소를 늘리지 않아 위빙이 정확합니다. |

### 설치

1. [Releases](../../releases)에서 `Lumelight3D-S25Ultra-*.apk`를 받아 설치합니다.
2. 처음 3D를 켤 때 **카메라 권한**을 허용합니다(눈 추적용).
3. 설정 › 3D 출력 = **무안경 3D (SpatialGlass 필름)**, 3D 소스는 PC가 보내는 화면에 맞게 고릅니다.

기존 Lumelight·Moonlight와 패키지가 달라(`com.limelight.glass.noirdebug`) 함께 설치할 수 있습니다.

### 잘 보는 법

- **거리 30~40 cm**에서 보세요. 20 cm처럼 너무 가까우면 두 눈에 같은 그림이 들어가 2D가 됩니다.
- **앞카메라가 왼쪽**에 오게 가로로 듭니다. 3D를 켜면 이 방향으로 고정됩니다(반대 방향이면 색이 갈라져 보입니다).
- 3D가 겹치거나 뒤집혀 보이면 **3D 조절 › 위상**을 천천히 움직여 가장 선명한 곳에 둡니다. 머리를 옆으로 옮길 때 깨지면 **추적**(1.5~2.5)을 조절합니다.

### 빌드

`nonRoot_gameGlass` 플레이버입니다(`src/glass/`). Leia 바이너리는 필요 없습니다.

```
gradlew :app:assembleNonRoot_gameGlassDebug
```

필름 렌즈 값(간격 1.51207, 기울기 −0.19057)은 S25 Ultra(SM-S938N)에서 측정한 값이며 `GlassSettings`에 들어 있습니다. 다른 폰·필름에서는 맞지 않을 수 있습니다.

Leia 태블릿용 원래 설명은 [docs/README.leia.md](docs/README.leia.md)에 있습니다.

---

## English

The **Galaxy S25 Ultra + SpatialGlass 3D film** edition of [Lumelight 3D](https://github.com/nautymac/Lumelight-3D), built on [Artemis](https://github.com/ClassicOldSong/moonlight-android) (a Moonlight Android fork). Stream PC games and see them in 3D on the phone, no glasses.

Unlike the Leia tablets, the film is **only a lens**: the phone has no 3D engine of its own. So the app does it itself:

- **Weaving**: both eyes' pictures are mixed per pixel to match the film's lenses, with blending curves measured separately for red, green and blue.
- **Eye tracking**: the front camera finds both irises about 60 times a second and moves the weave with your head, so 3D holds as you move. Everything stays on the phone; no video leaves it.

### Features

| Feature | What it does |
|---|---|
| **3D from the PC's side-by-side** | 3D Source = *Side by Side (host sends 3D)*. Geo-11, 3D Vision, SBS videos and the like are woven for the film. |
| **2D games in 3D** | 3D Source = *2D to 3D (AI)*. The phone's depth model makes the 3D. |
| **Three-finger hold (1 s)** | Switches between plain side-by-side and film 3D at any time, whatever the touch settings. |
| **3D tune bar** | While in 3D, tap **3D tune** (top right) to adjust phase, tracking strength, brightness and contrast while watching. Values are saved. |
| **Brightness and contrast lift** | Film 3D is dimmer because each eye gets only part of the pixels. The picture is lifted by default (brightness 1.4, contrast 1.15), adjustable. |
| **16:9 dead centre** | Any resolution (1080p included) sits in the exact centre with equal bars, and pixels are never stretched, so the weave stays exact. |

### Install

1. Download `Lumelight3D-S25Ultra-*.apk` from [Releases](../../releases) and install it.
2. Allow the **camera** the first time 3D turns on (for eye tracking).
3. Settings › 3D Output = **Glasses-Free 3D (SpatialGlass film)**; pick the 3D Source that matches what your PC sends.

It has its own package (`com.limelight.glass.noirdebug`), so it installs next to other Lumelight/Moonlight builds.

### Getting a good picture

- Watch from **30–40 cm**. Much closer (about 20 cm) both eyes see the same view and it turns 2D.
- Hold the phone in landscape with the **front camera on the left**; 3D locks to that orientation (the other way round the colours split).
- If 3D looks doubled or inside-out, slide **3D tune › Phase** slowly to the sharpest point. If it breaks as you move your head sideways, adjust **Track** (1.5–2.5).

### Build

It is the `nonRoot_gameGlass` flavour (`src/glass/`). No Leia binaries are needed.

```
gradlew :app:assembleNonRoot_gameGlassDebug
```

The film's lens values (pitch 1.51207, slant −0.19057) were measured on an S25 Ultra (SM-S938N) and live in `GlassSettings`; other phones or films may need different values.

The original Leia tablet documentation is in [docs/README.leia.md](docs/README.leia.md).

---

Not affiliated with Samsung, the film's maker, Leia, Moonlight or Artemis. Licensed under GPLv3 like Moonlight.
