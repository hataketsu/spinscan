# Bàn xoay — firmware ET4000+

Firmware bare-metal cho board EasyThreeD ET4000+ (STM32F103RCT6, tương thích MKS
Robin Lite). Board chỉ làm đúng một việc: **giữ yên cho máy ảnh chụp, kêu một
tiếng, xoay một nấc, lặp lại** — điều khiển và chỉnh tham số qua cổng serial.
Không Klipper, không host, không bootloader.

    loader     316 bytes
    slot A/B  7424 bytes mỗi bản

## Chân dùng

| Chân | Việc |
|---|---|
| PC6 | STEP của driver X |
| PB12 | DIR |
| PB10 | ENABLE X/Y/Z, tích cực mức thấp |
| PB0 | Vref dòng X/Y, PWM 250 kHz trên TIM3_CH3 |
| PD2 | còi (nằm trên header EXP1 — không cắm LCD thì đặt `BEEP_ENABLE 0`) |

Vi bước (microstep) trên board này do jumper dưới driver quyết định, không phải
phần mềm. Jumper đang là bao nhiêu thì `MICROSTEPS` trong `config.h` phải đúng
bấy nhiêu, nếu không góc xoay sẽ sai theo đúng tỉ lệ đó.

## Sửa thông số

Tất cả nằm trong `firmware/config.h`:

```c
#define SHOTS_PER_REV 120     /* 120 nấc = 3 độ mỗi nấc */
#define INTERVAL_MS   3000    /* cửa sổ đứng yên, khớp với app */
#define MICROSTEPS    16
#define GEAR_NUM 1
#define GEAR_DEN 1            /* bàn gắn thẳng trục motor */
#define DIRECTION 1           /* -1 để đảo chiều */
#define CURRENT_DUTY 0.30f    /* dòng driver; 0.40 là mức của máy in */
```

Một vòng 120 nấc × 3 s = **6 phút**, vừa khít với pipeline dựng hình.

## Bố cục flash

256 KiB flash, firmware 7,4 KiB — thừa chỗ để giữ **hai bản đầy đủ**:

```
0x08000000    8 KiB   loader      chọn slot rồi nhảy; không bao giờ ghi qua UART
0x08002000   56 KiB   slot A      firmware
0x08010000   56 KiB   slot B      firmware
0x0801E000    2 KiB   metadata    slot nào đang sống, kèm checksum
0x0803F800    2 KiB   settings    tham số, nằm ngoài cả hai slot
```

Cập nhật **luôn ghi vào slot đang không chạy**, xong mới lật một chữ trong
metadata. Mất điện giữa chừng thì slot đang sống vẫn nguyên vẹn — trường hợp xấu
nhất là "bản mới không vào được", không bao giờ là "board chết".

Loader kiểm tra checksum slot được đánh dấu sống; hỏng thì thử slot kia; hỏng cả
hai thì **nhảy thẳng vào bootloader ROM**, tức là board có hai ảnh hỏng vẫn cứu
được qua đúng sợi cáp USB đã làm hỏng nó. Board mới nạp lần đầu chưa có metadata
thì loader chạy slot A nếu nó trông giống một ảnh hợp lệ.

Hai slot là cùng một code link ở hai địa chỉ khác nhau — vector table và literal
pool của Cortex-M là địa chỉ tuyệt đối, không dời ảnh sau khi build được. Vì thế
`FLASH` từ chối ảnh link cho sai slot trước khi xoá bất cứ trang nào.

## Build

```bash
cd turntable/firmware
make
```

Cần `gcc-arm-none-eabi` (`sudo apt install gcc-arm-none-eabi`). Ra:

| File | Việc |
|---|---|
| `combined.bin` | loader + slot A, nạp bằng ST-Link ở `0x08000000` |
| `turntable-a.bin` / `turntable-b.bin` | ảnh cho từng slot, dùng khi cập nhật |
| `../../dist/turntable-fw.json` | manifest (size, crc32, sha256 mỗi slot) cho máy chủ |

## Nạp bằng ST-Link V2

```bash
sudo apt install stlink-tools        # hoặc openocd
cd turntable/firmware
make flash                            # st-flash write combined.bin 0x08000000
```

Dùng OpenOCD thay thế:

```bash
make flash-openocd
```

Nối 4 dây tới header SWD của board: **SWDIO = PA13**, **SWCLK = PA14**, `GND`,
`3V3`. Kiểm tra đã bắt được chip:

```bash
sudo st-info --probe          # chipid 0x414 = thông; 0x000 = chưa
```

`Target voltage` đọc ra ~3,3 V mà `chipid` vẫn `0x000` nghĩa là nguồn và GND đã
thông nhưng SWDIO/SWCLK thì chưa — hay gặp nhất là hai dây đó bị đảo. Không có
dây NRST thì `--connect-under-reset` cũng vô dụng; cách thủ công là giữ nút
reset của board, chạy lệnh probe, rồi thả nút.

## Nạp qua UART, không cần ST-Link

STM32F103 có sẵn bootloader trong ROM, nói chuyện đúng trên USART1 — cũng chính
là cổng đã nối vào CH340. Nếu SWD không chịu lên thì đây là đường vòng gọn hơn
nhiều so với việc dò dây:

```bash
sudo apt install stm32flash
# kéo BOOT0 lên 1 (jumper hoặc nối BOOT0 vào 3V3), rồi cấp nguồn lại
stm32flash /dev/ttyUSB0                                   # nhận diện chip
stm32flash -w turntable.bin -v -g 0x08000000 /dev/ttyUSB0 # nạp và chạy
# trả BOOT0 về 0, cấp nguồn lại
```

Đường này không đụng gì tới option byte, nhưng nếu board đang bật chống đọc
(RDP) thì bootloader sẽ từ chối ghi và phải gỡ bằng SWD trước.

**Board của máy in thường bị khoá đọc/ghi từ nhà sản xuất.** Nếu `st-flash` báo
không ghi được, gỡ khoá trước — thao tác này **xoá sạch firmware gốc của máy in,
không lấy lại được**, nên chỉ làm khi đã chấp nhận biến board thành bàn xoay:

```bash
make erase-openocd        # stm32f1x unlock + mass erase
```

Cấp nguồn 12/24 V cho board khi nạp; ST-Link chỉ đủ nuôi phần logic, không đủ
cho driver motor.

## Cách nó chạy

```
cấp nguồn
  ├─ 72 MHz (HSE 8 MHz × 9), flash 2 wait state
  ├─ PB0 phát PWM 250 kHz → Vref = 30 % dòng
  ├─ PB10 xuống thấp → driver giữ bàn
  ├─ bíp dài 150 ms = sẵn sàng
  └─ lặp mãi:
       bíp 40 ms  ← chụp lúc này
       đứng yên INTERVAL_MS
       xoay một nấc (ramp lên, chạy đều, ramp xuống)
```

Vị trí đếm bằng vi bước và mỗi đích được tính lại từ chỉ số nấc
(`shot × steps_per_rev / shots`), nên nếu số nấc không chia hết cho một vòng thì
phần dư được rải đều thay vì dồn thành một khe hở ở chỗ khép vòng. Với 120 nấc
trên 3200 vi bước, các nấc sẽ là 27/27/26 xen kẽ — sai số góc không tích luỹ.

Ramp cố tình chậm (3000 µs → 1200 µs, 12 bước). Bước nhanh không tiết kiệm được
gì đáng kể — mất vài chục ms — nhưng làm vật rung lâu hơn sau khi bàn đã dừng, mà
đó mới là thứ làm hỏng khung hình.

## Điều khiển qua UART

USART1 (PA9/PA10) đi thẳng ra chip CH340 trên board, nên cắm cáp USB vào máy là
có `/dev/ttyUSB0`. **115200 8N1**, mỗi dòng một lệnh, mỗi lệnh một dòng trả lời
`ok` hoặc `err ...`. Không framing, không checksum — để terminal, script Python
và điện thoại Android qua OTG đều nói được mà không phải cài gì.

```
screen /dev/ttyUSB0 115200          # hoặc: picocom -b 115200 /dev/ttyUSB0
```

| Lệnh | Việc |
|---|---|
| `?` | đổ toàn bộ trạng thái dạng `key=value` |
| `HELP` | liệt kê lệnh và tên tham số |
| `SET <key> <val>` | đổi một tham số, có kiểm tra khoảng hợp lệ |
| `GET <key>` | đọc một tham số |
| `SAVE` / `LOAD` / `DEFAULTS` | ghi vào flash / nạp lại / về mặc định |
| `RUN` / `STOP` | chạy / dừng chuỗi hẹn giờ |
| `SYNC` | đặt lại cửa sổ đứng yên từ lúc này, không xoay |
| `STEP` | xoay ngay một nấc |
| `JOG <steps>` | xoay thô theo vi bước, có dấu |
| `TURN <deg>` | xoay theo độ, có dấu |
| `GOTO <shot>` | về đúng nấc số mấy |
| `ZERO` | lấy vị trí hiện tại làm nấc 0 |
| `ON` / `OFF` | giữ/nhả mô-men |
| `BEEP [hz] [ms]` | kêu một tiếng |
| `FLASH <size> <crc32>` | nhận ảnh firmware rồi ghi vào slot trống |
| `DFU` | nhảy vào bootloader ROM của STM32 |

Tham số đặt được: `shots interval micro motor gearnum gearden dir current dstart
dmin ramp beephz beepms beep`.

```
> SET shots 90
shots=90
ok
> SET interval 4000
interval=4000
ok
> SAVE
ok
> RUN
ok
```

`SAVE` ghi vào trang flash cuối (2 KiB ở `0x0803F800`), có magic và checksum;
lần khởi động sau tự nạp lại, `saved_settings=1` trong dòng chào. Trang bị ghi
dở đọc ra toàn `0xFF` nên checksum trượt và firmware quay về mặc định thay vì
chạy với tham số rác.

`RUN` **không** tự chạy lúc cấp nguồn. Bàn tự quay ngay khi cắm điện đúng lúc
đang đặt vật lên là thứ không ai muốn.

`SYNC` là cách máy ảnh khớp lại nhịp: gọi nó ngay sau khi chụp xong một khung
thì cửa sổ đứng yên tính lại từ thời điểm đó.

## Cập nhật firmware, không cần tháo gì

Hai đường, cùng đi qua sợi cáp USB đang cắm sẵn.

**Đường thường — `FLASH`, tự ghi vào slot trống.** Board nhận nguyên ảnh vào RAM,
kiểm CRC-32, kiểm con trỏ stack có nằm trong SRAM không, kiểm ảnh có đúng link
cho slot đích không; sai bất cứ điều nào thì nó trả lỗi và **chưa hề xoá gì**.
Đúng hết mới xoá và ghi. Đoạn ghi flash được link vào RAM và chạy từ đó, vì
F103 chỉ có một bank: đang xoá flash thì không thể vừa nạp lệnh từ flash.

```
> FLASH 7424 3929157247
ready
<7424 byte nhị phân>
ok writing slot 1, do not cut power
turntable ready
```

Đo thật trên board: 7424 byte đi hết 0,64 s ở 115200, ghi và reboot xong trong
khoảng 2 s nữa. Các ca hỏng đã thử: ảnh sai slot → `err image linked for the
wrong slot`; CRC sai → `err crc mismatch`; cả hai đều không đụng tới flash.

**Đường cứu hộ — `DFU`.** Firmware tự nhảy vào bootloader nằm trong ROM của
STM32 (`0x1FFFF000`), không cần jumper BOOT0, không cần ST-Link. Sau lệnh đó cổng
chuyển sang giao thức bootloader ở **8E1**:

```bash
stm32flash /dev/ttyUSB0                                      # nhận diện
stm32flash -w combined.bin -v -g 0x08000000 /dev/ttyUSB0     # nạp lại từ đầu
```

ROM không xoá được nên đường này luôn còn, kể cả khi cả hai slot đều hỏng —
loader tự nhảy vào đó khi không tìm được ảnh nào chạy được.

## Đồng bộ với app

Board và điện thoại chạy hai đồng hồ riêng, không có dây nào nối giữa chúng. Đặt
cùng chu kỳ (3 s cả hai) thì hai bên trôi tương đối chậm, và app đã có cửa chặn
rung: nó hoãn màn trập tới khi gia tốc kế báo yên, tối đa ~1,2 s. Nấc xoay chỉ
mất ~50 ms nên cửa sổ đứng yên chiếm gần trọn chu kỳ — khung hình rơi vào lúc
đang xoay là hiếm, và khi rơi vào thì app tự đợi.

Muốn chắc chắn tuyệt đối thì phải có đường trigger từ board sang máy ảnh; điện
thoại không có cổng đó nên cách duy nhất là cho server ra lệnh chụp. Chưa làm.
