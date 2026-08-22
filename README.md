# COLMAP 3D reconstruction test rig

Reconstruct a 3D object from photos taken all around it (photogrammetry / SfM + MVS).

## Setup

Host already has: Docker, NVIDIA Container Toolkit, RTX 2080 Ti.
COLMAP runs from the official CUDA image `colmap/colmap:latest` — the Ubuntu apt
package is built **without CUDA**, so `patch_match_stereo` (dense reconstruction)
would not run at all.

```bash
docker pull colmap/colmap:latest
```

## Layout

```
images/      <- put your photos here (jpg/png)
workspace/   <- all COLMAP output
  database.db
  sparse/0/  <- camera poses + sparse point cloud
  dense/
    fused.ply           <- dense point cloud
    meshed-poisson.ply  <- mesh
colmap.sh    <- runs any colmap subcommand inside the docker image
recon.sh     <- full pipeline
```

## Run

```bash
# 1. drop photos into images/
# 2. run
./recon.sh
```

Tuning via env vars:

```bash
MAX_SIZE=1200 ./recon.sh              # faster/lower-res dense stage
MATCHER=sequential ./recon.sh         # photos are an ordered video-like sweep
MESHER=both ./recon.sh                # poisson + delaunay meshes
./recon.sh /path/to/imgs /path/to/out # explicit dirs (must be inside this project)
```

Any raw subcommand:

```bash
./colmap.sh model_analyzer --path /working/workspace/sparse/0
./colmap.sh gui   # needs X11 forwarding, see below
```

## Shooting the photos (this matters more than any flag)

- 40–150 photos, orbit the object in 2–3 rings at different heights.
- **~70–80% overlap** between neighbouring shots. Small steps, not big jumps.
- Fixed focal length. No zoom mid-shoot. Manual focus if possible.
- Even, diffuse light. No harsh shadows moving with the object.
- Textured, matte surfaces reconstruct well. Shiny / transparent / plain-white
  objects reconstruct badly or not at all.
- **Move the camera, not the object.** If you use a turntable, the background
  moves relative to the object and SfM will latch onto the background instead.
  Fix: a plain uniform backdrop plus masks (see below).

### Masks (turntable case)

Put a mask PNG per photo in `masks/`, named `<photo_filename>.png`
(e.g. `IMG_0001.jpg` -> `masks/IMG_0001.jpg.png`), black = ignore, white = keep,
then add to the feature extractor:

```bash
--ImageReader.mask_path /working/masks
```

## Viewing results

```bash
sudo apt install meshlab      # or: blender, cloudcompare
meshlab workspace/dense/fused.ply
```

## GUI (optional)

```bash
xhost +local:docker
docker run --rm --gpus all -e DISPLAY=$DISPLAY \
  -v /tmp/.X11-unix:/tmp/.X11-unix \
  -v "$PWD:/working" -w /working colmap/colmap:latest colmap gui
```

## Runtime expectations (RTX 2080 Ti, 24 cores)

| Stage | 50 photos @ 12MP |
|---|---|
| feature_extractor | ~1 min |
| exhaustive_matcher | ~1–2 min |
| mapper | ~2–5 min |
| patch_match_stereo | ~10–30 min (dominant cost; scales with `MAX_SIZE`) |
| stereo_fusion + mesh | ~2–5 min |

`exhaustive_matcher` is O(n²) — fine to ~200 photos. Past that use `vocab_tree`.

## Troubleshooting

- **mapper makes no `sparse/0`** — not enough overlap/texture, or every photo
  registered against the background. Shoot more, smaller steps.
- **multiple models `sparse/0`, `sparse/1`** — the set split into disconnected
  clusters. Use the biggest one, or shoot connecting photos.
- **`patch_match_stereo` OOM** — lower `MAX_SIZE` (1500, then 1000).
- **Sparse/holey dense cloud** — the object is shiny or untextured; dust it with
  matte spray or add texture to the scene.

## Chế độ nhanh (PRESET=fast)

`patch_match_stereo` chiếm ~95% thời gian, và chi phí của nó gần như tuyến tính
theo số pixel × số vòng lặp × số mẫu, còn `geom_consistency` thì chạy lại toàn bộ
lượt quét lần hai. Preset `fast` cắt cả bốn:

| | normal | fast |
|---|---|---|
| feature max_image_size | 3200 | 1600 |
| matcher | exhaustive | sequential |
| dense max_size | 2000 px | 700 px |
| geom_consistency | true | false (fusion dùng photometric, `min_num_pixels 4`) |
| patch match iterations / samples / window_radius | 5 / 15 / 5 | 3 / 8 / 3 |
| Poisson depth | 13 | 9 |

Đo trên 118 ảnh, RTX 2080 Ti, cùng bộ ảnh:

| giai đoạn | normal | fast |
|---|---|---|
| feature_extractor | 0.6 ph | 0.33 ph |
| matcher | 0.36 ph | 0.33 ph |
| mapper | 2.6 ph | 2.2 ph |
| image_undistorter | 0.45 ph | 0.39 ph |
| **patch_match_stereo** | **96.0 ph** | **2.8 ph** |
| stereo_fusion + poisson | ~2.5 ph | 0.2 ph |
| **tổng** | **~103 ph** | **6.3 ph** |

Kết quả `fast`: 1.13M điểm dense, mesh 133k mặt, STL 55k mặt sau khi bỏ mặt phẳng
nền. Khối và biến dạng giữ nguyên; chi tiết nổi trên bề mặt thì mất. Dùng khi cần
mẫu để in hoặc để kiểm tra hình, không dùng khi cần bề mặt nét.

```bash
PRESET=fast ./recon.sh /abs/path/images /abs/path/workspace
```

## Phân tích bằng LLM

`analyze.py` đo trước rồi mới hỏi model:

* **đo** (không cần mạng): độ nét từng ảnh (variance của Laplacian), phơi sáng,
  vùng cháy/tối, và độ phủ góc máy đọc ngược từ sparse model — khoảng hở azimuth
  lớn nhất, số tầng elevation, dao động khoảng cách.
* **hỏi**: gửi bảng số liệu + 6 ảnh mẫu (có ảnh mờ nhất) tới gateway
  OpenAI-compatible, nhận về nhận xét tiếng Việt và danh sách việc cần làm.

Cấu hình trong `.env` (chmod 600, đã nằm trong `.gitignore`):

```
OPENAI_BASE_URL=https://<gateway>/v1     # bất kỳ endpoint OpenAI-compatible nào
OPENAI_API_KEY=sk-...
OPENAI_MODEL=claude-haiku-4-5
```

```bash
./.venv/bin/python analyze.py projects/<tên>            # đo + gợi ý
./.venv/bin/python analyze.py projects/<tên> --no-llm   # chỉ số liệu
```

Trên web: nút **Chấm ảnh + gợi ý cải thiện**. Kết quả cache ở
`projects/<tên>/.analysis.md`, tự xoá khi chạy lượt dựng mới.

## Tự động ra STL tốt nhất

`meshkit.py` là tầng tham số hoá: đám mây điểm -> STL in được, mọi tham số nằm
trong một `Params` duy nhất, thứ tự cố định

    poisson -> bóc mặt phẳng nền -> giữ thành phần -> vá -> làm mượt -> giảm mặt -> scale

Vá trước khi làm mượt, vì mép lỗ kéo bộ lọc mượt vào trong và để lại gờ; giảm mặt
sau khi làm mượt, vì làm mượt một mesh đã giảm mặt chỉ bào tròn đúng những góc mà
bước giảm mặt vừa cố giữ.

`autotune.py` quét không gian tham số đó và chấm bằng **hai giám khảo không ngang
hàng nhau**:

* **điểm số** — khách quan, tính từ chính đám mây điểm dày: sai lệch (mesh bịa ra
  bề mặt), độ phủ (mesh làm mất bề mặt), kín/hở, số mảnh rời, và số **lỗ xuyên bị
  lấp**. Đây là thứ xếp hạng. Nó không bịa được.
* **critic** — LLM nhìn ảnh render 3 ứng viên đầu, chỉ để bắt cái mà số liệu mù:
  đáy không phẳng, bề mặt nhão vì làm mượt quá, vân artifact, mảnh rác. Nó chỉ
  được đảo thứ tự trong nhóm 3 ứng viên mà điểm số đã chọn, không tự lật kèo.

Lỗ xuyên đo bằng đặc trưng Euler: vá một biên hở làm Euler +1, bịt một lỗ xuyên
làm +2. Phần dư sau khi trừ một-trên-mỗi-biên chính là số lỗ đã bị lấp. Nhờ đó
`pymeshfix` không còn thắng nhờ bịt luôn lỗ bắt vít để đạt "kín".

Lưu ý phạm vi: chỉ số này so với mesh Poisson sinh ra, không so với vật thật. Lỗ
mà Poisson chưa bao giờ mở ra thì không ai phát hiện được.

```bash
./.venv/bin/python autotune.py projects/<tên> [--scale-to 80] [--target-faces 80000] [--no-llm]
```

Đo trên nema2fast (36 tổ hợp, ~3,7 phút): thắng cuộc `depth 10, trim 7, meshfix`
— 354.746 mặt, kín, sai lệch RMS 0,49% cỡ vật, phủ 96,1%, không mất lỗ xuyên.
Kết quả ghi ra `dense/auto-best.stl` + `dense/autotune.json`. Trên web: nút
**Quét tham số + chấm mesh**.

Cảnh báo về `--target-faces`: giảm mặt mạnh (336k -> 80k) làm sập lỗ nhỏ và mở
seam. Cả hai đều đã được đo và bị trừ điểm, nhưng nếu bạn ép mức giảm thì vẫn
phải trả giá đó — bảng kết quả ghi rõ số lỗ bị lấp.

## App Android

Nguồn ở `android/`, Kotlin + Gradle, giao diện Material 3 (AndroidX). Một
Activity duy nhất với **thanh điều hướng dưới**: Chụp / Project / Bàn xoay.

```bash
cd android
./gradlew assembleDebug              # ra app/build/outputs/apk/debug/app-debug.apk
./gradlew clean publishOta           # bản phát hành: chép sang dist/ + ghi latest.json
```

`clean` là cố ý: build tăng dần đôi khi giữ lại bộ ngôn ngữ cũ của
Material/AppCompat và APK phình thêm ~600 KB. Bản đi OTA thì build sạch.

Phiên bản nằm trong `android/app/build.gradle.kts` (`versionCode`/`versionName`),
không nằm trong manifest. `publishOta` ghi `dist/collmap.apk` +
`dist/latest.json`; máy chủ phục vụ hai file đó ở `/api/app/download` và
`/api/app/latest`.

Bản build ký bằng `~/.android/debug.keystore` — **không đổi khoá**: Android từ
chối cập nhật đè lên bản đã cài nếu chữ ký khác, và mọi máy đang dùng sẽ phải gỡ
cài đặt bằng tay.

Trong app:

* **Chụp hẹn giờ cho bàn xoay** — chu kỳ 2/3/4/5/6/8/12 s, khớp với vòng quay bàn.
* **Khoá AE/AF/WB** một lần cho cả loạt. Để tự động thì mỗi khung hình là một máy
  ảnh hơi khác nhau, trong khi COLMAP đang hiệu chuẩn *một* máy ảnh cho tất cả.
* **Chờ hết rung** mới bấm máy — bàn xoay dừng bước trước khi hết dao động.
* **Tắt chống rung quang học** — OIS dịch thấu kính giữa các khung, làm xê dịch
  đúng cái tâm quang mà COLMAP coi là hằng số.
* **Chỉ báo góc ngẩng**, vì chụp một tầng độ cao duy nhất là kiểu hỏng phổ biến
  nhất; ảnh được gắn nhãn `low`/`mid`/`high` theo tầng.
* **Giới hạn 12 MP** mặc định: COLMAP hạ xuống 1600–3200 px để trích đặc trưng,
  còn ảnh 50 MP là ~10 MB mỗi khung phải bò qua WiFi.
* **Gửi từng ảnh ngay**, một request một ảnh, thử lại một lần.

### OTA

App hỏi `/api/app/latest`, so `versionCode`, tải APK, đối chiếu SHA-256 mà máy
chủ khai, rồi đưa file cho `PackageInstaller`. Hệ thống vẫn hiện hộp thoại xác
nhận của nó — đây là tự phân phối, không phải cài ngầm. Lần đầu vẫn phải bật
"cài từ nguồn không xác định" cho Collmap; app tự mở đúng trang cài đặt đó.

Dùng `PackageInstaller` session chứ không phải `ACTION_VIEW` lên file URI: từ
Android 7 đường đó cần FileProvider, và từ Android 12 nó không còn đáng tin cho
việc tự cập nhật.

## Rig bàn xoay: mask và cắt ảnh

Bàn xoay phá đúng giả định COLMAP dựa vào. Nó muốn cảnh đứng yên và máy ảnh di
chuyển; nó nhận được cảnh xoay và máy ảnh đứng yên. Đặc trưng trên tường sau bàn
đồng thanh khẳng định máy ảnh không hề nhúc nhích, đặc trưng trên đĩa xoay khẳng
định máy ảnh đi vòng quanh, và mapper phải chọn một. Nền càng nhiều hoa văn thì
càng dễ chọn nhầm.

`make_mask.py` cho nó chỉ nhìn thấy phần đang xoay. Không cần dò hình tròn, không
cần vẽ tay: lấy mẫu vài chục khung rải đều, tính **độ lệch chuẩn theo từng pixel**,
bàn xoay sáng rực lên còn căn phòng phẳng lì. Phần còn lại chỉ là dọn dẹp hình
thái học rồi bọc bằng một ellipse — bàn xoay nhìn nghiêng vốn là ellipse, và
ellipse gọn thì giữ được trọn vành đĩa nơi có các mã ArUco.

Vì máy ảnh **không di chuyển**, vùng đó nằm nguyên một chỗ ở mọi khung, nên
**một mask dùng chung cho cả bộ** — bình thường đây mới là phần đắt đỏ của việc
masking. Trên đĩa 115 ảnh: 115 file mask cùng hard link về một ảnh duy nhất.

```bash
./.venv/bin/python make_mask.py projects/<tên> --crop --preview /tmp/xem.jpg
```

`--crop` cắt luôn mọi khung về quanh vùng giữ, ghi ra `images_cropped/` kèm
`masks_cropped/` khớp kích thước. Cắt đáng giá hơn mask đơn thuần vì cùng một cảm
biến sẽ dồn pixel cho vật thay vì cho căn phòng.

Có một cái bẫy ở đây: COLMAP đọc `FocalLengthIn35mmFilm` trong EXIF rồi quy ra
pixel theo chiều rộng ảnh. Cắt ảnh làm hẹp cảm biến hiệu dụng mà không đổi ống
kính, nên trị số 35 mm tương đương phải **tăng đúng bằng tỉ lệ cắt**. Giữ nguyên
trị số cũ là đưa cho mapper một tiêu cự sai theo đúng hệ số đó — tệ hơn cả việc
không có prior nào. Script tự nhân lại và in ra hệ số đã dùng.

Chạy có mask:

```bash
MASK_DIR=/abs/path/masks_cropped PRESET=fast \
  ./recon.sh /abs/path/images_cropped /abs/path/workspace
```

Trên web: nút **Tạo mask + cắt ảnh**, rồi tick **Dùng mask khi dựng**.

Đo trên bộ `test` (115 ảnh, đĩa ArUco, máy ảnh cố định, **không** dùng mask):
115/115 ảnh định vị, một model duy nhất, 54.908 điểm, 591.168 quan sát,
**5.141 quan sát/ảnh**, sai số tái chiếu **0,79 px**. Nghĩa là với đĩa ArUco đủ
đặc trưng, nền tĩnh không đủ sức phá — mask là bảo hiểm, không phải điều kiện
sống còn. Nền càng trơn và vật càng ít đặc trưng thì nó càng cần thiết.

## Xem trực tiếp và điều khiển từ web

Máy chủ giữ đúng một khung hình preview cho mỗi project trong RAM và một hộp thư
lệnh nhỏ. Không WebSocket, không thư viện mới, và **khung hình không bao giờ ghi
xuống đĩa** — đó là ảnh xem tạm, ghi ra chỉ tổ băm nát thư mục project.

| Endpoint | Việc |
|---|---|
| `POST /api/projects/{tên}/live` | app đẩy JPEG thô lên, tối đa 2 MB |
| `GET /api/projects/{tên}/live.jpg` | trang web lấy về, khung cũ quá 10 s coi như mất tín hiệu |
| `GET /api/projects/{tên}/live/status` | `{alive, age_ms}` |
| `POST /api/projects/{tên}/command` | `shoot` / `start` / `stop` / `lock` / `unlock` |
| `GET /api/projects/{tên}/command?wait=25` | app long-poll, trả lệnh cũ nhất |

Khung cũ hiển thị như đang trực tiếp còn tệ hơn là thú nhận mất tín hiệu, nên quá
10 giây là trả 404 chứ không trả ảnh cũ.

## Bù độ sâu bằng mạng nơ-ron (`neural_depth.py`)

`patch_match_stereo` chỉ ghi độ sâu ở chỗ nó khớp được vân bề mặt giữa các
khung. Nhựa bóng, kim loại trần, mặt gương thì không có gì để khớp, pixel trả về
0 và vật đơn giản là không có hình học ở đó. Mạng đơn ảnh thì luôn trả lời —
đúng vì thế mà nó **không bao giờ được phép đè lên số đo**. Đo thắng suy diễn;
suy diễn chỉ dành cho chỗ chưa đo được gì.

```bash
./.venv/bin/python neural_depth.py projects/<tên> [--model vits|vitb|vitl] [--max-fill 0.35]
```

Lần chạy đầu tự cài `torch`/`torchvision` (wheel CUDA 12.4) và `transformers`
vào `.venv` nếu chưa có, rồi tải Depth Anything V2 về `~/.cache/huggingface`.
Mặc định `vits`. `vitb` đã được đo song song: khác biệt không sống sót qua sàn
nhiễu của các chỉ số mesh, còn trên chỉ số tất định (điểm bù nằm cách bề mặt đo
được bao xa) thì hai bên đổi chỗ cho nhau — `vits` đặt ít điểm ra xa hơn
(0,091 % so với 0,153 % số điểm), `vitb` đặt nhiều hơn nhưng gần hơn một chút.
Chưa lần nào model nhỏ tỏ ra là chỗ nghẽn, nên `vitl` cố tình chưa thử: 1,3 GB
trọng số để trả lời một câu chưa ai hỏi.

### Chỗ khó duy nhất: lấy lại tỉ lệ

Depth Anything trả về **nghịch đảo độ sâu tương đối**: không tỉ lệ, không gốc.
Quan hệ affine chỉ đúng trong không gian `1/z`, nên phép khớp làm ở đó:

    1/z_colmap  ≈  s · pred + t

và hai số `s, t` được đòi lại từ chính depth map của COLMAP, trên những pixel
COLMAP *đã* thành công. Bình phương tối thiểu thô không dùng được: depth map
photometric có cái đuôi ngoại lai dài tới vài lần kích thước cảnh, chỉ vài điểm
là kéo lệch cả khung. Nên: RANSAC trên từng cặp điểm để tìm đồng thuận, rồi ba
vòng IRLS (Tukey) tinh lại trên toàn bộ inlier. Ngưỡng inlier là **tương đối**
(`|q̂−q| < 3 %·q`) — trong không gian nghịch đảo, sai số tương đối bằng đúng sai
số tương đối tính bằng mét, nên một ngưỡng dùng chung cho gần và xa.

Khung nào khớp không đạt (inlier < 45 %, hoặc `s ≤ 0`, hoặc phải lấp quá
`--max-fill` khung hình) thì **bỏ hẳn**, không lấp. Mất một chút độ phủ còn hơn
nhét vào một mặt sai tỉ lệ — mặt đó đầu độc cả những khung chồng lấn với nó.

Đo trên bộ `2` (240 ảnh): inlier trung vị **95–97 %**, sai số tương đối trung vị
**0,46–0,58 %**. Nghĩa là mô hình đơn ảnh khớp với hình học đo được tới quanh
nửa phần trăm — phần khớp tỉ lệ không phải chỗ yếu.

### Cách ghi ra

Depth map bù ghi vào cây song song `workspace/neural/`, `images/` và `sparse/`
hard link như `preview.sh` làm (2 GB, chỉ đọc, link tốn một inode). `depth_maps/`
và `normal_maps/` thì **tuyệt đối không link** — ghi qua hard link là ghi thẳng
vào inode gốc, tức là phá luôn depth map cổ điển mà cả phép so sánh dựa vào.
File nào sửa thì tạo mới, file nào giữ nguyên mới được link.

Normal map phải có thì `stereo_fusion` mới chạy. Normal cho pixel được lấp tính
bằng sai phân trung tâm trên chính depth map đã bù, trong hệ toạ độ máy ảnh, lấy
nội tham số từ `sparse/cameras.bin`; chỗ COLMAP có đo thì giữ nguyên normal của
COLMAP. Pixel nào không lấy được normal (4 điểm lân cận không đủ, hoặc vắt qua
mép lỗ) thì trả độ sâu về 0 luôn — thà bỏ còn hơn đưa cho fusion một normal chỉ
vào hư không.

`--StereoFusion.min_num_pixels` đọc ngược từ `run.log` của lượt dựng cũ, **không**
nới ra. Kiểm tra nhất quán đa góc nhìn của fusion là tuyến phòng thủ thứ hai:
một mặt suy diễn mà không góc nào khác đồng ý thì vẫn phải bị loại.

Lưu ý về `colmap::Mat`: header ASCII `width&height&channels&`, phần dữ liệu là
**slice-major** (`slice*w*h + row*w + col`), không phải xen kẽ kênh. Đọc normal
map theo kiểu xen kẽ ra một mảng trông rất hợp lý mà độ dài còn chẳng bằng 1.

### Đo xem có ích thật không

Mesh hai bên dựng cùng công thức (`meshkit.Params()`, Poisson depth 9) và chấm
trên **cùng một** đám mây tham chiếu — đám mây cổ điển. Chấm mỗi mesh bằng đám
mây của chính nó là để cho điểm suy diễn tự bảo lãnh cho mặt do nó sinh ra.

**A. Depth map photometric** (đúng cái `PRESET=fast` sinh ra), 240 ảnh, `vits`:

| | cổ điển | có bù |
|---|---|---|
| điểm | 1.227.986 | 1.229.194 (+0,10 %) |
| mặt | 61.558 | 61.900 |
| kín | có | có |
| sai lệch RMS % | 0,6192 ±0,0023 | 0,6372 ±0,0023 |
| độ phủ | 0,8910 | 0,8909 |
| không được đỡ | 0,2410 ±0,0006 | 0,3069 ±0,0009 |
| điểm meshkit | 31,84 | 28,73 |

**B. Depth map geometric** (chạy thêm lượt `geom_consistency` trên một bản sao):

| | cổ điển | có bù |
|---|---|---|
| điểm | 928.672 | 936.747 (+0,87 %) |
| mặt | 69.552 | 69.650 |
| kín | có | có |
| sai lệch RMS % | 0,5539 ±0,0014 | 0,5512 ±0,0020 |
| độ phủ | 0,5689 | 0,5690 |
| không được đỡ | 0,2406 ±0,0008 | 0,2408 ±0,0003 |
| điểm meshkit | 19,50 | 19,52 |

### Đừng tin bảng trên: nó nằm dưới sàn nhiễu

Hai bảng đó nói ngược nhau, nên phải làm phép đối chứng rỗng: chạy lại đúng
pipeline với `--max-fill 0`, tức **không lấp một pixel nào**, chỉ fuse lại
workspace cũ. `stereo_fusion` chạy đa luồng nên đám mây lệch đi *chín điểm* trên
1,23 triệu. Kết quả:

| | cổ điển (mesh cache) | fuse lại, KHÔNG bù gì |
|---|---|---|
| điểm | 1.227.986 | 1.227.977 (−9) |
| không được đỡ | 0,2405 | **0,2795** |
| điểm meshkit | 31,89 | **29,99** |

Chín điểm trên 1,23 triệu làm `unsupported` nhảy 0,039 và điểm meshkit rơi 1,9 —
lớn hơn toàn bộ khác biệt giữa cổ điển và có bù. Qua năm lượt fuse cùng một bộ
dữ liệu, con số đó chạy từ 0,23 tới 0,31. Nguyên nhân: Poisson là một phép giải
toàn cục rồi cắt tỉa, hỗn loạn theo tập điểm đầu vào. **Mọi so sánh ở mức mesh
dưới ±0,08 đều vô nghĩa**, kể cả những dòng có độ lệch chuẩn đẹp đẽ ±0,002 —
độ lệch đó chỉ đo việc lấy mẫu bề mặt, không đo việc dựng lại mesh.

Nên `neural_depth.py` báo thêm một phép so **tất định**, không qua Poisson:
khoảng cách từ mỗi điểm trong đám mây có bù tới điểm ĐO ĐƯỢC gần nhất.

| | A (photometric) | B (geometric) |
|---|---|---|
| điểm xa hơn 0,4 % cỡ vật | 37 / 1.229.194 (0,003 %) | 855 / 936.747 (0,091 %) |
| khoảng cách đó, trung vị | 0,466 % | 0,642 % |
| p95 | 0,632 % | 1,052 % |

Đây mới là câu trả lời: phần bù **không** treo lơ lửng. Gần như toàn bộ điểm suy
diễn nằm sát ngay bề mặt đã đo, chỉ 0,003–0,09 % trôi ra xa và cũng chỉ xa
khoảng 0,5–1 % cỡ vật. Kiểm tra nhất quán đa góc nhìn của `stereo_fusion` đã làm
đúng việc của tuyến phòng thủ thứ hai.

### Nhận định thẳng

**Cơ chế chạy đúng; trên rig này nó gần như không có việc để làm.**

1. Phép khớp tỉ lệ hoạt động tốt hơn mong đợi: inlier trung vị **95–97 %**, sai
   số tương đối trung vị **0,46–0,58 %**. 240/240 khung đạt ngưỡng, không khung
   nào phải bỏ. Phần khó nhất của bài toán không phải chỗ yếu.
2. Nhưng lỗ thì gần như không có. `PRESET=fast` chỉ sinh depth map
   *photometric*, mà patch match photometric gán độ sâu cho **gần như mọi
   pixel** — lọc thật diễn ra sau, lúc fusion. Lỗ thật trong mask: **0,072 %**
   khung hình. Chạy thêm lượt `geom_consistency` mới ra lỗ thật (0,47 % khung
   hình, nằm đúng trên vỏ kim loại cổng USB/Ethernet như dự đoán) — vẫn chỉ
   **+0,87 %** số điểm.
3. Ở mức mesh thì không đo được gì, vì bước Poisson nhiễu gấp mười lần hiệu ứng
   cần đo.

Nói gọn: **không có bằng chứng nào cho thấy nó làm mesh tốt lên, cũng không có
bằng chứng nào cho thấy nó làm hỏng.** Nó thêm 0,1–0,9 % số điểm, đặt đúng chỗ,
và biến mất trong nhiễu. Với 240 ảnh quanh một vật, hình học đã đo đủ dày; tiên
nghiệm học sẵn không thêm được gì mà số liệu khách quan chịu công nhận.

Chỗ nó *có thể* đáng dùng — chưa có bộ ảnh nào để kiểm chứng — là bộ thưa
(30–50 khung) hoặc vật thật sự trơn bóng, tức đúng cái tình huống mà phần
"holes" chiếm hàng chục phần trăm khung hình chứ không phải nửa phần trăm. Giữ
lại như một lựa chọn phụ, **không** đưa vào đường mặc định của `recon.sh`.

Cách tái lập bảng B (cây song song, không đụng vào kết quả cổ điển):

```bash
G=projects/2geom/workspace/dense; D=projects/2/workspace/dense
mkdir -p $G/stereo && cp -al $D/images $G/images && cp -al $D/sparse $G/sparse
cp -a $D/stereo/{depth_maps,normal_maps} $G/stereo/    # copy, KHÔNG hard link:
cp $D/stereo/{patch-match.cfg,fusion.cfg} $G/stereo/   # pass 2 sẽ ghi đè
mkdir -p $G/stereo/consistency_graphs
./colmap.sh patch_match_stereo --workspace_path /working/$G \
  --PatchMatchStereo.geom_consistency true \
  --PatchMatchStereo.num_iterations 3 --PatchMatchStereo.num_samples 8 \
  --PatchMatchStereo.window_radius 3 --PatchMatchStereo.filter_min_ncc 0.15
./colmap.sh stereo_fusion --workspace_path /working/$G --input_type geometric \
  --StereoFusion.min_num_pixels 5 --output_path /working/$G/fused.ply
./.venv/bin/python neural_depth.py projects/2geom
```

COLMAP bỏ qua ảnh nào đã có depth map, nên chép sẵn bản photometric vào là nó
chỉ chạy lượt hai: ~11 phút cho 240 ảnh ở 700 px thay vì gấp đôi.

Chi phí: ~28 s suy luận (240 ảnh, RTX 2080 Ti, `vits`, batch 8) + ~22 s fusion +
~1 phút dựng mesh so sánh. Cây `workspace/neural/` (~2 GB) tự xoá sau fusion,
giữ lại bằng `--keep-workspace`.
