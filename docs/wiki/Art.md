# 基础功法美术

## 来源与范围

全部主体由 CPA cliproxy 的 `gpt-image-2.5-flare` 生成，`quality=high`、PNG、
`style=none`，提示词明确指定 Minecraft 像素风。未使用其他后端或 Claude 生图额度。
调用复用 Bong `scripts/images/gen.py` 的 `generate_cliproxy_images` 图片接口；
任务专用参数位于交接目录，未改生成器默认值和全局配置。

先由 flare、sunburst 分别生成木门背景与叩门图标。最终选 flare：中央正文区平均灰度
228.51（sunburst 227.22），两者都接近浅纸；同一最近邻 32×32 采样下，
叩门的颜色数为 375（sunburst 407），flare 略便于收敛为低色数图标。
sunburst 的正文区灰度标准差略低（1.52 对 1.64）；选择依据是亮度和缩小后的颜色收敛，
不代表所有题材上 flare 都优于 sunburst。正文区域按 x=18%–82%、y=12%–88% 测量。

## 成品与资源约定

资源命名空间为 `meridian_basic_techniques`。卷轴和背景沿用功法库的完整卷 ID 路径，
战斗图标沿用目录 `presentation.icon_id` 的路径。资源随正式 Fabric JAR 打包。
只提供纹理，不增加卷轴注册、阅读界面、动作栏显示或其他接线代码。

| 书卷 | 卷路径 | 32×32 卷轴图 | 1536×1024 阅读背景 | 提示词 |
|---|---|---|---|---|
| 调息卷 | `breath_volume` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/item/breath_volume_scroll.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/scroll/breath_volume.png) | 卷轴 [提示词](#prompt-breath-scroll)；背景 [提示词](#prompt-breath-background) |
| 轻步卷 | `light_step_volume` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/item/light_step_volume_scroll.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/scroll/light_step_volume.png) | 卷轴 [提示词](#prompt-light-step-scroll)；背景 [提示词](#prompt-light-step-background) |
| 蓄力卷 | `charge_volume` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/item/charge_volume_scroll.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/scroll/charge_volume.png) | 卷轴 [提示词](#prompt-charge-scroll)；背景 [提示词](#prompt-charge-background) |

| 招式 | icon_id | 32×32 | 64×64 | 提示词 |
|---|---|---|---|---|
| 调息 | `meridian_basic_techniques:breath` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/breath.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/breath_64.png) | [提示词](#prompt-breath) |
| 轻步 | `meridian_basic_techniques:light_step` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/light_step.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/light_step_64.png) | [提示词](#prompt-light-step) |
| 蓄力 | `meridian_basic_techniques:charge` | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/charge.png) | [PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/gui/technique/charge_64.png) | [提示词](#prompt-charge) |

## 后处理

轻身状态图标为 [18×18 RGBA PNG](../../fabric/src/main/resources/assets/meridian_basic_techniques/textures/mob_effect/light_body.png)。
由 CPA `gpt-image-2.5-flare` 图片接口生成像素羽毛与风纹，沿用米白、古金、炭褐配色；
最近邻缩小、24 色量化并移除暗底，保留原版效果图标的透明背景，无粒子资源。
原稿与完整提示词分别位于交接目录
`light-step-leap-work/art/light_body_pixel.png`、`light-step-leap-work/light-body-pixel-prompt.txt`。
客户端实拍同时核验 HUD 图标及地面落点框。

- 阅读背景保留 1536×1024 RGB；主题细节集中在外缘，中央浅纸供运行时正文使用，提示词禁止文字。
- 方形生成请求为 1024×1024，CPA 实际返回 1254×1254 RGB；记录实际尺寸后再处理。
- 卷轴按 R、B≥130、G≤140 且 R−G、B−G≥60 去品红底，裁去透明边，以最近邻缩至 30×30 以内；
  透明像素 RGB 归零后无抖动量化为 32 色，再居中放进 32×32 RGBA，alpha 二值化为 0 或 255。
- 战斗图标先最近邻缩为 64×64，把与主要暗底 RGB 距离≤20 的颜色归为统一炭褐底，
  无抖动量化为 32 色，再统一两圈边框：外圈 `#201813`、内圈 `#846437`。
  64×64 每圈宽 2 像素；最近邻缩为 32×32 后每圈宽 1 像素。加入边框后最多 34 色。
  动作主体保留 CPA 原稿。
- 后处理不绘制或替换动作主体。尺寸、PNG 解码、alpha、透明留边、边框及目录 ID 对照由脚本核验。

## 原稿、比较样张与复现

为遵守仅新增 assets 与本页的协作范围，原稿、日志和独立提示词留在仓外交接目录，
未创建仓内 `.local-art/`、未改 `.gitignore`；逐图提示词全文归档在本页，避免增加其他 Wiki 文件。

- 原稿：`/home/kiz/Code/Kizuna-remote-session/handoff/art-basic/raw`。
- 独立提示词：`/home/kiz/Code/Kizuna-remote-session/handoff/art-basic/prompts`；叩门实际使用 `/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/prompts/knock_icon.txt`。
- flare 样张：`/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/samples/flare/upper_background.png`、
  `/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/samples/flare/knock.png`。
- sunburst 样张：`/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/samples/sunburst/upper_background.png`、
  `/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/samples/sunburst/knock.png`。
- 两组对照预览：`/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/samples/comparison.jpg`。
- 生成与后处理：`/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/cpa_generate.py`、`/home/kiz/Code/Kizuna-remote-session/handoff/technique-art-20261028-work/process_art.py`。
- 2.5 模型经通用 Responses 路由曾返回 503；改用生成器已有图片入口后成功，失败日志保留在交接目录。
- 暂存与正式资源的同内容副本：`/home/kiz/Code/Kizuna-remote-session/handoff/art-basic/assets`。
- 三个卷 ID 以 `_volume` 结尾；因此卷轴为 `breath_volume_scroll.png` 等，
  背景为 `breath_volume.png` 等，战斗图标仍为 `breath.png` 等。

## 逐图提示词

以下保留实际发送内容；代码块仅按词换行。

<a id="prompt-breath-scroll"></a>

### breath_scroll

```text
One single inventory item sprite: 调息卷. Subject: a gently curved narrow scroll bound with a jade-green cord
and a tiny unlettered red seal; soft celadon and ivory, balanced rounded silhouette. Minecraft vanilla-style
pixel art designed on a 32x32 logical pixel grid, shown enlarged in a square image. Shared collection
identity: thick stepped dark umber outline, restrained brass detail, flat clustered shading and limited
colors; make this volume's silhouette and palette clearly distinct from the other two volumes of its book.
Center one fully visible object occupying about 80 percent of canvas. Solid pure magenta #FF00FF background
only for chroma key, no magenta on the object. Crisp block pixels, no antialiasing, gradients, glow,
shadows, photorealism, other objects, UI, frame, writing, letters, symbols or watermark.
```

<a id="prompt-breath-background"></a>

### breath_background

```text
Create a production-ready landscape 1536x1024 Minecraft-compatible pixel-art reading background for 调息卷.
Straight-on orthographic view of one antique Chinese martial manual scroll. Shared binding language: a slim
dark umber edge, squared scroll rods at far left and right, restrained aged brass corner fittings, worn
paper edges. Theme: quiet jade-green vapor curls and a small vermilion seal at the outer corners; pale
celadon and charcoal, calm breathing rhythm. Place all themed detail in the outermost 15 percent margins.
Keep the central rectangle from x=18% to 82% and y=12% to 88% as uninterrupted very light cream parchment,
evenly lit and low contrast for dark live-rendered body text. Crisp clustered square pixels, limited
palette, no photorealism, no perspective or cast shadow. Absolutely no text, Chinese characters, letters,
numerals, calligraphy, writing-like marks, title plates, watermark, logo, people or diagrams. Full-bleed
opaque texture.
```

<a id="prompt-light-step-scroll"></a>

### light_step_scroll

```text
One single inventory item sprite: 轻步卷. Subject: a slim half-open scroll with pale blue ties and light silver
end caps; airy blue-grey and ivory, a noticeably narrow diagonal silhouette. Minecraft vanilla-style pixel
art designed on a 32x32 logical pixel grid, shown enlarged in a square image. Shared collection identity:
thick stepped dark umber outline, restrained brass detail, flat clustered shading and limited colors; make
this volume's silhouette and palette clearly distinct from the other two volumes of its book. Center one
fully visible object occupying about 80 percent of canvas. Solid pure magenta #FF00FF background only for
chroma key, no magenta on the object. Crisp block pixels, no antialiasing, gradients, glow, shadows,
photorealism, other objects, UI, frame, writing, letters, symbols or watermark.
```

<a id="prompt-light-step-background"></a>

### light_step_background

```text
Create a production-ready landscape 1536x1024 Minecraft-compatible pixel-art reading background for 轻步卷.
Straight-on orthographic view of one antique Chinese martial manual scroll. Shared binding language: a slim
dark umber edge, squared scroll rods at far left and right, restrained aged brass corner fittings, worn
paper edges. Theme: small wind-carved shoe prints and thin cloud bands along the outer margins; mist blue,
silver and dark ink, light directional rhythm. Place all themed detail in the outermost 15 percent margins.
Keep the central rectangle from x=18% to 82% and y=12% to 88% as uninterrupted very light cream parchment,
evenly lit and low contrast for dark live-rendered body text. Crisp clustered square pixels, limited
palette, no photorealism, no perspective or cast shadow. Absolutely no text, Chinese characters, letters,
numerals, calligraphy, writing-like marks, title plates, watermark, logo, people or diagrams. Full-bleed
opaque texture.
```

<a id="prompt-charge-scroll"></a>

### charge_scroll

```text
One single inventory item sprite: 蓄力卷. Subject: a compact tightly rolled scroll wrapped in a muted cinnabar
band with dark iron end caps; restrained red and charcoal, dense squared silhouette. Minecraft vanilla-style
pixel art designed on a 32x32 logical pixel grid, shown enlarged in a square image. Shared collection
identity: thick stepped dark umber outline, restrained brass detail, flat clustered shading and limited
colors; make this volume's silhouette and palette clearly distinct from the other two volumes of its book.
Center one fully visible object occupying about 80 percent of canvas. Solid pure magenta #FF00FF background
only for chroma key, no magenta on the object. Crisp block pixels, no antialiasing, gradients, glow,
shadows, photorealism, other objects, UI, frame, writing, letters, symbols or watermark.
```

<a id="prompt-charge-background"></a>

### charge_background

```text
Create a production-ready landscape 1536x1024 Minecraft-compatible pixel-art reading background for 蓄力卷.
Straight-on orthographic view of one antique Chinese martial manual scroll. Shared binding language: a slim
dark umber edge, squared scroll rods at far left and right, restrained aged brass corner fittings, worn
paper edges. Theme: subtle concentric pressure rings and compressed brush-like force marks at the outer
corners; muted cinnabar, dark iron and pale ivory, gathered stillness. Place all themed detail in the
outermost 15 percent margins. Keep the central rectangle from x=18% to 82% and y=12% to 88% as uninterrupted
very light cream parchment, evenly lit and low contrast for dark live-rendered body text. Crisp clustered
square pixels, limited palette, no photorealism, no perspective or cast shadow. Absolutely no text, Chinese
characters, letters, numerals, calligraphy, writing-like marks, title plates, watermark, logo, people or
diagrams. Full-bleed opaque texture.
```

<a id="prompt-breath"></a>

### breath

```text
One square Minecraft martial-technique hotbar icon for 调息. Action silhouette: a small centered seated
breathing silhouette with two balanced curling breath lines rising from the chest; calm symmetrical rhythm,
no lettering. Authentic crisp pixel art designed as exactly 32x32 logical pixels, enlarged in a square
1024x1024 image with large flat pixel clusters and stepped silhouettes. Shared Door Breaker series frame:
exactly one logical pixel dark umber outer rim, one logical pixel dull brass inner rim, flat charcoal-brown
interior. Shared Basic Techniques icons use this same frame and palette. Foreground uses high-contrast
ivory, oak brown and desaturated steel, with one small muted amber or cinnabar action accent. Keep the
action large, instantly recognizable and inside the frame. No photorealism, 3D, gradients, antialiasing,
tiny ornament, full scene, text, glyphs, numbers, letters, watermark, logo or UI labels. Fill the square
tile.
```

<a id="prompt-light-step"></a>

### light_step

```text
One square Minecraft martial-technique hotbar icon for 轻步. Action silhouette: two small offset boot prints
with short pale wind streaks flowing forward; strong diagonal light-footed direction, no full person.
Authentic crisp pixel art designed as exactly 32x32 logical pixels, enlarged in a square 1024x1024 image
with large flat pixel clusters and stepped silhouettes. Shared Door Breaker series frame: exactly one
logical pixel dark umber outer rim, one logical pixel dull brass inner rim, flat charcoal-brown interior.
Shared Basic Techniques icons use this same frame and palette. Foreground uses high-contrast ivory, oak
brown and desaturated steel, with one small muted amber or cinnabar action accent. Keep the action large,
instantly recognizable and inside the frame. No photorealism, 3D, gradients, antialiasing, tiny ornament,
full scene, text, glyphs, numbers, letters, watermark, logo or UI labels. Fill the square tile.
```

<a id="prompt-charge"></a>

### charge

```text
One square Minecraft martial-technique hotbar icon for 蓄力. Action silhouette: a planted boot beside a
compact weapon hilt with three short energy strokes compressing inward toward the grip; grounded stillness
and gathered force. Authentic crisp pixel art designed as exactly 32x32 logical pixels, enlarged in a square
1024x1024 image with large flat pixel clusters and stepped silhouettes. Shared Door Breaker series frame:
exactly one logical pixel dark umber outer rim, one logical pixel dull brass inner rim, flat charcoal-brown
interior. Shared Basic Techniques icons use this same frame and palette. Foreground uses high-contrast
ivory, oak brown and desaturated steel, with one small muted amber or cinnabar action accent. Keep the
action large, instantly recognizable and inside the frame. No photorealism, 3D, gradients, antialiasing,
tiny ornament, full scene, text, glyphs, numbers, letters, watermark, logo or UI labels. Fill the square
tile.
```
