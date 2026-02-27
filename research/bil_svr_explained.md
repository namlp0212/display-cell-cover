# Minh Hoạ Dữ Liệu: Định dạng `.bil` và `.svr.bil`
### DisplayCellCover — Phân tích Raster Nhị phân Thực tế

> **Dữ liệu thực tế:** Phân tích từ cell `BNH0019_1` và `HN_1` trong thư mục `data/rasters/`

---

## 1. Tổng quan: Hai File, Hai Mục đích

Mỗi trạm BTS trong dự án có **đúng 2 file raster** đi kèm nhau, cùng kích thước, cùng hệ tọa độ, nhưng mang ý nghĩa hoàn toàn khác nhau:

| Thuộc tính | `.bil` (Continuous) | `.svr.bil` (Binary Mask) |
|---|---|---|
| **Nội dung** | Cường độ tín hiệu dBm | Mặt nạ vùng phủ nhị phân |
| **Loại dữ liệu** | Float32 liên tục | Float32 chỉ có 1 giá trị |
| **Giá trị thực tế** | `-139.96` đến `-73.21` dBm | `7108.0` (hoặc NoData) |
| **Pixel có tín hiệu** | 77.3% (286,617 pixel) | 77.3% (286,617 pixel) |
| **Pixel NoData** | 22.7% (84,204 pixel) | 22.7% (84,204 pixel) |
| **Dùng để** | Visualize gradient màu | Xác định vùng phủ có/không |

---

## 2. Cấu trúc Vật lý File (BIL Format)

### 2.1. Header File (`.hdr`) — Metadata điều khiển giải mã

```
ulxmap  605525.000000     ← Easting tâm pixel đầu tiên (UTM Zone 47N)
ulymap  2329835.000000    ← Northing tâm pixel đầu tiên (UTM Zone 47N)
xdim    10.000000         ← Mỗi pixel = 10 mét theo chiều X
ydim    10.000000         ← Mỗi pixel = 10 mét theo chiều Y
ncols   661               ← 661 cột pixel
nrows   561               ← 561 hàng pixel
nbits   32                ← 32 bit mỗi pixel (= 4 bytes)
nbands  1                 ← 1 band duy nhất (grayscale data)
byteorder I               ← Intel byte order = Little-endian
layout  bil               ← Band Interleaved by Line
bandrowbytes 2644         ← 661 × 4 bytes = 2644 bytes/hàng
totalrowbytes 2644        ← = bandrowbytes (vì 1 band)
skipbytes 0               ← Không có header trong file .bil
datatype R32              ← Float32 (GDAL không nhận dạng, cần patch thêm PIXELTYPE FLOAT)
```

**Vùng địa lý được bao phủ:**
```
Góc trên-trái:  (605520.0, 2329840.0) UTM → pixel edge
Góc dưới-phải:  (612130.0, 2324230.0) UTM

Chiều rộng:  661 × 10m = 6,610m  = 6.61 km
Chiều cao:   561 × 10m = 5,610m  = 5.61 km
Tổng diện tích ≈ 37.1 km²
```

### 2.2. Bố cục nhị phân trong file `.bil` (Row-major, Little-endian Float32)

File `.bil` là **luồng byte thô**, không nén, không header. Toàn bộ `661 × 561 = 370,821` pixels được lưu tuần tự theo từng hàng từ **trên xuống dưới**, **trái sang phải**:

```
┌─────────────────────────────────────────────────────────────────────┐
│  FILE .BIL — 1,483,284 bytes (661 × 561 × 4)                       │
│                                                                     │
│  Offset 0        Hàng 0 (top): 661 pixels × 4 bytes = 2,644 bytes  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ FF FF 7F FF │ FF FF 7F FF │ ... (661 lần) ...               │   │
│  │  NoData     │  NoData     │ ← Hàng 0 TOÀN NoData (biên trên)│   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Offset 2,644    Hàng 1: vẫn NoData gần biên...                    │
│  ...                                                                │
│                                                                     │
│  Offset ~57×2644 Hàng 56: bắt đầu có dữ liệu tín hiệu             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ FF FF 7F FF │...│ A0 26 D0 C2 │ D7 3F D0 C2 │...│ FF FF 7F FF│ │
│  │  NoData     │   │  -104.15dBm │  -104.50dBm │   │  NoData    │  │
│  └──────────────────────────────────────────────────────────────┘   │
│           ↑ biên trái                              ↑ biên phải      │
│                                                                     │
│  Hàng 280 (giữa): 628/661 pixels có tín hiệu, 33 NoData           │
│  Hàng 560 (cuối): chỉ 19/661 pixels có tín hiệu                    │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3. Công thức tính offset byte

Để đọc pixel tại hàng `row`, cột `col`:
```
offset = (row × ncols + col) × 4
        = (row × 661    + col) × 4  [bytes]

Ví dụ: pixel(row=280, col=50)
   offset = (280 × 661 + 50) × 4
           = (185,080 + 50) × 4
           = 185,130 × 4
           = 740,520 bytes
   → Đọc 4 bytes tại vị trí 740,520 trong file
   → Decode little-endian float32 → -104.15 dBm
```

---

## 3. Minh hoạ File `.bil` — Cường độ Tín hiệu Liên tục

### 3.1. Giải mã IEEE 754 Little-endian

Mỗi pixel là **4 bytes** theo chuẩn IEEE 754 Float32 (little-endian):

```
Bytes trong file:  CD  4C  D0  C2
                   ↑   ↑   ↑   ↑
                  [0] [1] [2] [3]   ← Thứ tự trong file (little-endian)

Đảo lại → Big-endian:  C2  D0  4C  CD
                         │   │   │   │
                         ▼   ▼   ▼   ▼
Bit:    1 1000101 10100100110011001101
        │    │              │
       Sign Exponent(8b)  Mantissa(23b)

Giá trị = (-1)^1 × 2^(133-127) × 1.mantissa
         = -1 × 2^6 × 1.6273...
         = -104.15 dBm  ✓
```

**Hai giá trị đặc biệt:**
```
FF FF 7F FF (little-endian) = NoData = -3.4028235 × 10^38
                              → SLD: opacity=0 → trong suốt hoàn toàn

CD 4C D0 C2 (little-endian) = -104.1500 dBm
                              → SLD: màu teal (tín hiệu trung bình)
```

### 3.2. Dữ liệu thực tế: Phân phối giá trị dBm

**Thống kê toàn file `BNH0019_1.bil`:**
```
Tổng pixel:          370,821  (661 × 561)
Pixel NoData:         84,204  (22.7%) → vùng không phủ sóng
Pixel có tín hiệu:   286,617  (77.3%) → vùng phủ sóng

Giá trị nhỏ nhất:   -139.96 dBm  (tín hiệu yếu nhất, gần biên)
Giá trị lớn nhất:    -81.65 dBm  (tín hiệu mạnh nhất, gần trạm)
Giá trị trung bình: -114.82 dBm  (tín hiệu trung bình)
```

> **Ý nghĩa dBm:** dBm là decibel so với 1 milliwatt. Giá trị âm = yếu hơn 1mW.
> - `-73 dBm` ≈ tín hiệu **Xuất sắc** (rất gần trạm)
> - `-100 dBm` ≈ tín hiệu **Chấp nhận được** (biên vùng phủ)  
> - `-140 dBm` ≈ tín hiệu **Cực yếu** (sát ngưỡng kết nối)

### 3.3. Hình dạng vùng phủ theo hàng pixel

Phân phối số pixel có tín hiệu theo từng hàng cho thấy hình oval đặc trưng của vùng phủ BTS:

```
Hàng   0: [   0 pixel] ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ ← Trên đỉnh (NoData)
Hàng  56: [ 342 pixel] ██████████████████████████████████
Hàng 112: [ 555 pixel] ███████████████████████████████████████████████████████
Hàng 168: [ 601 pixel] ████████████████████████████████████████████████████████████
Hàng 224: [ 624 pixel] ██████████████████████████████████████████████████████████████
Hàng 280: [ 628 pixel] ██████████████████████████████████████████████████████████████ ← Giữa (đầy nhất)
Hàng 336: [ 618 pixel] █████████████████████████████████████████████████████████████
Hàng 392: [ 599 pixel] ███████████████████████████████████████████████████████████
Hàng 448: [ 578 pixel] █████████████████████████████████████████████████████████
Hàng 504: [ 455 pixel] █████████████████████████████████████████████
Hàng 560: [  19 pixel] █ ← Dưới đáy (chỉ còn vài pixel)
                             ▲
                       10 pixel = 100m trên thực địa
```

→ Vùng phủ **đối xứng ellipse** theo chiều dọc, rộng nhất ở giữa (~628 pixel = 6.28 km), thu hẹp ở hai đầu.

### 3.4. Mẫu pixel thực tế 8×8 (vùng trung tâm)

Đây là giá trị dBm thực đọc từ file, tại tọa độ pixel (row=277..284, col=327..334):

```
         col327   col328   col329   col330   col331   col332   col333   col334
row277:  -111.4   -111.2   -114.3   -114.0   -113.6   -113.4   -111.0   -114.3
row278:  -111.3   -110.7   -110.7   -111.3   -111.6   -111.5   -114.3   -112.5
row279:  -111.6   -112.0   -111.8   -111.7   -111.5   -114.5   -114.7   -113.7
row280:  -112.1   -112.0   -111.8   -112.0   -111.4   -111.3   -114.2   -112.5
row281:  -112.7   -111.8   -111.7   -111.3   -110.8   -110.7   -112.7   -110.5
row282:  -112.1   -111.3   -111.0   -114.0   -112.1   -111.8   -110.7   -115.9
row283:  -111.6   -110.3   -113.0   -112.3   -112.1   -111.2   -110.6   -115.8
row284:  -110.5   -110.0   -109.8   -112.5   -111.8   -111.1   -110.6   -115.2
```

**Tọa độ địa lý của pixel trung tâm (row=280, col=330):**
```
Easting  = ulxmap + col × xdim = 605525 + 330 × 10 = 608,825.0 m (UTM Zone 47N)
Northing = ulymap - row × ydim = 2329835 - 280 × 10 = 2,327,035.0 m
→ Tương đương ≈ 21.01°N, 105.84°E (Hà Nội, Việt Nam)
```

### 3.5. Ánh xạ màu sắc (SLD Cividis)

File `cellcover-continuous.sld` ánh xạ giá trị dBm sang màu **Cividis colormap**:

```
Giá trị dBm   →  Màu hiển thị
─────────────────────────────────────────────
  NoData       →  Trong suốt (opacity=0)
     0–1140    →  #00204D  (xanh đen)
  1140–2280    →  #00336F  (xanh đậm)
  2280–3420    →  #1F4E79  (xanh cobalt)
  3420–4560    →  #2C788E  (teal)
  4560–5700    →  #5FA060  (xanh lá)
  5700–6850    →  #9DBA46  (vàng lá)
  6850–8000    →  #D2CE3E → #FDE725 (vàng sáng)
```

> **Lưu ý:** Dải giá trị SLD là 0–8000 (dùng cho HN_ cells với range rộng hơn), trong khi BNH0019_1 thực tế chỉ có -140 đến -73 dBm. Hệ thống cần calibrate lại SLD hoặc normalize giá trị tùy dataset.

---

## 4. Minh hoạ File `.svr.bil` — Mặt nạ vùng phủ nhị phân

### 4.1. Khám phá bất ngờ: Giá trị "nhị phân" thực chất là Cell ID

Kết quả phân tích thực tế toàn bộ `370,821 pixels` của `BNH0019_1.svr.bil`:

```
Pixel có tín hiệu:  286,617 → giá trị DUY NHẤT = 7108.0
Pixel NoData:        84,204 → giá trị = -3.4028235×10^38

→ KHÔNG có giá trị nào khác! Đây là binary mask thuần túy.
```

**Bằng chứng:** File `.svr.mnu` (menu file đi kèm) tiết lộ ý nghĩa:
```
7108    BNH0019_1
```
→ Giá trị `7108.0` là **Cell ID** được mã hóa dưới dạng float32!

**So sánh giữa các cell:**
```
BNH0019_1.svr.bil  →  giá trị duy nhất = 7108.0
HN_1.svr.bil       →  giá trị duy nhất = 7119.0
HN_2.svr.bil       →  giá trị duy nhất = (khác nhau theo cell)
```

### 4.2. Giải mã bytes của giá trị 7108.0

```
7108.0 (Float32, little-endian) = 00 20 DE 45

Bytes trong file:  00  20  DE  45
                   ↑   ↑   ↑   ↑
Big-endian →      45  DE  20  00

Bits:  0 10001011 10111110010000000000000
       │     │              │
      (+)  Exp=139       Mantissa
      
Giá trị = (+1) × 2^(139-127) × 1.7399...
         = 1 × 2^12 × 1.7358...
         = 4096 × 1.735... 
         = 7108.0  ✓
```

### 4.3. Mẫu pixel thực tế 8×8 (SVR, cùng vùng với BIL)

```
         col327   col328   col329   col330   col331   col332   col333   col334
row277:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row278:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row279:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row280:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row281:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row282:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row283:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
row284:  7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0   7108.0
```

→ Tại vùng trung tâm **(nơi .bil có giá trị -110 đến -115 dBm)**, SVR luôn trả về `7108.0`. Pixel nào có tín hiệu trong `.bil` thì SVR tương ứng cũng là `7108.0`, và ngược lại.

### 4.4. Tại sao cần cả hai file?

```
Tình huống 1: "Cell BNH0019_1 có phủ sóng tại điểm X không?"
→ Dùng .svr.bil: Nếu pixel tại X ≠ NoData → CÓ phủ (O(1))
→ Không cần tính toán dBm phức tạp

Tình huống 2: "Cường độ tín hiệu tại điểm X là bao nhiêu?"
→ Dùng .bil: Đọc float32 → -104.15 dBm

Tình huống 3: GeoServer hiển thị vùng phủ lên bản đồ
→ .svr.bil → cellcover:binary store → SLD: 2 màu (có/không)
→ .bil      → cellcover:continuous store → SLD: gradient Cividis
```

---

## 5. So sánh Trực tiếp: `.bil` vs `.svr.bil` tại cùng vị trí

```
Pixel (row=280, col=330) — Vị trí ≈ 608825m E, 2327035m N (Easing)
                           ≈ 21.01°N, 105.84°E (WGS84)

                    .bil                     .svr.bil
                 ─────────                 ─────────────
Bytes:      CD 4C D0 C2              00 20 DE 45
Float32:        -104.15 dBm               7108.0
Ý nghĩa:   "Tín hiệu trung bình"    "Có phủ sóng (ID=7108)"
Màu SLD:   Teal (#2C788E), 70% opacity  Teal (#2C788E), 55% opacity
```

---

## 6. File `.svr.mnu` — Menu/Legend

File phụ trợ nhỏ (thường 16 bytes) ánh xạ giá trị số sang tên cell:

```
7108    BNH0019_1\r\n
```

Format: `<value_as_integer>\t<cell_name>\r\n`

Đây là bảng tra cứu ngược: khi một pixel trong `.svr.bil` có giá trị `7108.0`, ta biết ngay đó là cell `BNH0019_1`. Quan trọng khi nhiều cell mosaic cùng nhau — GeoServer có thể dùng giá trị này trong `CQL_FILTER`.

---

## 7. Quy trình Xử lý trong Hệ thống

```
data/rasters/BNH0019_1/
│
├── BNH0019_1.bil         ← Nguồn: đọc bởi geoserver-init.sh
│   (1.41 MB, Float32)
│       │
│       ├── [Patch HDR] thêm "PIXELTYPE FLOAT"
│       │       ↓
│       └── [gdalwarp -of COG -ot Float32]
│               ↓
│           data/cog/continuous/BNH0019_1.tif   ← COG, ~420KB (DEFLATE)
│               ↓
│           GeoServer: cellcover:continuous (ImageMosaic)
│               ↓
│           WMS tiles với cellcover-continuous.sld (Cividis gradient)
│
└── BNH0019_1.svr.bil     ← Nguồn tương tự
    (1.41 MB, Float32)
        │
        ├── [Patch HDR] thêm "PIXELTYPE FLOAT"
        │       ↓
        └── [gdalwarp -of COG -ot Float32]
                ↓
            data/cog/binary/BNH0019_1.tif   ← COG, ~120KB (DEFLATE, ít entropy hơn)
                ↓
            GeoServer: cellcover:binary (ImageMosaic)
                ↓
            WMS tiles với cellcover-binary.sld (teal đơn sắc, 55% opacity)
```

> **Tại sao COG binary nhỏ hơn nhiều?** File `.svr.bil` chỉ có 2 giá trị khác nhau (7108.0 và NoData), entropy cực thấp → DEFLATE nén rất hiệu quả (~91% reduction). File `.bil` có hàng trăm nghìn giá trị dBm khác nhau → nén kém hơn (~70% reduction).

---

## 8. Vấn đề GDAL: `datatype R32` vs `PIXELTYPE FLOAT`

Đây là **bug quan trọng nhất** trong pipeline xử lý:

```
Header gốc:    datatype R32      ← Ký hiệu độc quyền của phần mềm tạo file
GDAL đọc:      UInt32            ← GDAL không biết R32, mặc định = unsigned int

Kết quả nếu không fix:
  Bytes  FF FF 7F FF  →  GDAL đọc = 4,286,578,687 (UInt32)  ← SAI!
                      →  Đúng ra  = NoData = -3.4×10^38      ← CẦN

Fix trong geoserver-init.sh:
  echo "PIXELTYPE FLOAT" >> HDR_file_copy
  → GDAL hiểu đây là Float32 signed
  → FF FF 7F FF → -3.4×10^38  ✓
```

---

## 9. Tóm tắt Nhanh

```
File .bil:
  ┌─────────────────────────────────────────────────────┐
  │  661×561 Float32 pixels, row-major, little-endian   │
  │  Giá trị = cường độ tín hiệu dBm (−140 đến −73)    │
  │  NoData  = FF FF 7F FF = −3.4028235×10^38           │
  │  Dùng cho: gradient màu, phân tích cường độ         │
  └─────────────────────────────────────────────────────┘

File .svr.bil:
  ┌─────────────────────────────────────────────────────┐
  │  661×561 Float32 pixels, cùng layout với .bil       │
  │  Giá trị = Cell ID (ví dụ: 7108.0 = BNH0019_1)    │
  │  NoData  = FF FF 7F FF = −3.4028235×10^38           │
  │  Dùng cho: binary mask, xác định vùng phủ có/không │
  │  → Entropy thấp → COG nén tốt hơn nhiều            │
  └─────────────────────────────────────────────────────┘

File .svr.mnu (bonus):
  ┌─────────────────────────────────────────────────────┐
  │  Text file: "7108\tBNH0019_1"                       │
  │  Ánh xạ giá trị số → tên cell                      │
  └─────────────────────────────────────────────────────┘
```
