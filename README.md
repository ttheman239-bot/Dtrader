# Clip Capture — ถ่ายคลิปต้นแบบสำหรับ AI Live

แอป Android (Kotlin · Jetpack Compose · CameraX · MediaPipe · Room) สำหรับถ่ายคลิปสั้นหลายท่าที่
**เริ่มและจบที่ท่ากลางเดียวกัน** เพื่อนำไปสุ่มเล่นต่อกันเป็นตัวร่างกายของ AI Live
(ฝั่ง PC ใช้ LiveTalking + wav2lip/musetalk ทำปาก)

โค้ดอยู่ใน [`android/`](android/) · APK สร้างอัตโนมัติโดย GitHub Actions (**Clip Capture APK**) และแนบในหน้า Releases

## สถานะ milestone

| | ขอบเขต | สถานะ |
|---|---|---|
| **M1** | CameraX อัด 1080p 25 fps, Calibrate แล้วล็อก AE/AWB/โฟกัส, บันทึก Reference Frame, Room library | ✅ ทำแล้ว (รอทดสอบบนเครื่องจริง) |
| **M2** | MediaPipe face(blendshapes)/pose/hand real-time, Pose Match Score, ghost overlay, auto start/stop, เตือนปากเปิด/มือบังปาก/หน้าหลุดเฟรม, QC เบื้องต้นหลังถ่าย | ✅ ทำแล้ว (รอทดสอบบนเครื่องจริง) |
| M3 | QC ทุกเฟรม, loop points, export normalized clip (trim + 25 fps CFR) | ยังไม่ทำ |
| M4 | Alignment + color normalization | ยังไม่ทำ |
| M5 | Transition matrix + Sequence Preview + manual override | ยังไม่ทำ — หน้าจอแสดง “ยังไม่รองรับ” |
| M6 | Clip Package + manifest + Share / LAN upload | ยังไม่ทำ — หน้าจอแสดง “ยังไม่รองรับ” |
| M7 | โหมดเทคยาว ตัดอัตโนมัติ | ยังไม่ทำ |

ตามกฎ §10 ต้องรันผ่านบนเครื่องจริงก่อนไป M3 — ค่าเริ่มต้นใน Settings (§8) ยังต้องจูนกับวิดีโอจริง

## วิธีใช้ (M1–M2)

1. **Sessions → + สร้าง session** เลือกกล้องหน้า/หลัง และแนวตั้ง/แนวนอน
2. **ตั้งกล้อง / Calibrate / ถ่าย**: วางมือถือบนขาตั้ง → กด **Calibrate** (กล้องปรับอัตโนมัติ 2 วินาทีแล้วล็อก)
   - ถ้าเครื่องรองรับ MANUAL_SENSOR / MANUAL_POST_PROCESSING ค่าที่ได้ (exposure, ISO, white balance, focus)
     จะถูกบันทึกลง session และใช้ซ้ำแบบ manual ได้ในวันถัดไป (มีคำเตือนว่าแสงห้องอาจต่าง)
   - ถ้าไม่รองรับ จะใช้ AE/AWB lock และแจ้งว่าต้อง Calibrate ใหม่ทุกครั้ง
   - 25 fps: ใช้ช่วง AE `[25,25]` ถ้ามี หรือ manual frame duration 40 ms; ถ้าทำไม่ได้แอปจะบอกตรงๆ
3. นั่งท่ากลาง (ปากปิด เห็นไหล่สองข้าง มือไม่บังปาก) → **บันทึกท่ากลาง**
4. **Start**: แอปรอจน Pose Match ≥ `pose_match_start` ค้าง `neutral_hold_start_ms` → อัดเอง → แสดงคำแนะนำท่าแบบสุ่ม (calm/active)
   → ครบ `clip_min_sec` ขึ้น “กลับท่ากลาง” → หยุดเองเมื่อกลับท่ากลางค้าง `neutral_hold_end_ms`
   หรือครบ `clip_max_sec` (ติดธง `end_not_neutral`)
5. หลังแต่ละคลิปแสดง **QC เบื้องต้น** (ผ่าน / ควรถ่ายใหม่ + เหตุผล) จากการวิเคราะห์ระหว่างถ่าย —
   QC แบบถอดรหัสทุกเฟรมจากไฟล์อยู่ใน M3
6. **คลังคลิป**: ดู thumbnail, สถานะ, เหตุผล, tag, ลบคลิป

คลิปดิบ (ไม่มีเสียง) เก็บที่ `Android/data/com.dtrader.clipcapture/files/sessions/<id>/raw/raw_###.mp4`
พร้อม `reference.png` / `reference.json`

## Pose Match Score

คะแนน 0–100 = ค่าเฉลี่ยถ่วงน้ำหนักของ
- ระยะ landmark หน้า 13 จุด (หางตา จมูก มุมปาก คาง หน้าผาก แก้ม) หารด้วยระยะหางตาของ reference
- ต่างของมุมหัว yaw/pitch/roll (จาก facial transformation matrix)
- ตำแหน่งไหล่ทั้งสอง (หารด้วยความกว้างไหล่)
- ตำแหน่งข้อมือที่มองเห็น (หายไป/โผล่มาต่างจาก reference = ไม่ผ่านส่วนนั้น)

ทุกค่า tolerance / น้ำหนักอยู่ใน Settings

## Build

```bash
cd android
gradle assembleDebug   # Gradle 8.9+, JDK 17, Android SDK 35 — ดาวน์โหลดโมเดล MediaPipe อัตโนมัติ
gradle testDebugUnitTest
```

โมเดล: MediaPipe Face Landmarker, Pose Landmarker (lite), Hand Landmarker (Apache 2.0) ดาวน์โหลดตอน build
ลง `app/src/main/assets/models/` (ไม่ commit)

> เวอร์ชันก่อนหน้า (เว็บแอปคนดิจิทัล / 3D) ยังอยู่ในประวัติ git ของ branch นี้
