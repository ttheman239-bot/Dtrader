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
| M3 | QC ทุกเฟรม, loop points, export normalized clip (trim + 25 fps CFR) | บางส่วน: ตัดคลิปอัตโนมัติ + export trim/25 fps/no audio/keyframe 1s แล้ว; QC แบบถอดรหัสทุกเฟรมและเลือกเฟรมตัดที่นิ่งที่สุดยังไม่ทำ |
| M4 | Alignment + color normalization | ยังไม่ทำ |
| M5 | Transition matrix + Sequence Preview + manual override | ทำแล้ว (ยกเว้น manual override): heatmap จากเฟรมแรก/สุดท้ายของทุกคลิป, เล่นต่อเนื่องด้วย ExoPlayer 2 ตัว + crossfade, จำลองการพูดด้วย TTS (พูด → คลิป active, เงียบ → calm); lip-sync ยังไม่รองรับ |
| M6 | Clip Package + manifest + Share / LAN upload | ทำแล้ว: .zip (clips, reference, manifest.json พร้อม QC และ transition matrix) + แชร์; LAN upload ยังไม่รองรับ |
| M7 | โหมดเทคยาว ตัดอัตโนมัติ | ✅ เป็นโหมดถ่ายหลักแล้ว (ตัดจากการวิเคราะห์สด) |

ตามกฎ §10 ต้องรันผ่านบนเครื่องจริงก่อนไป M3 — ค่าเริ่มต้นใน Settings (§8) ยังต้องจูนกับวิดีโอจริง

## วิธีใช้

1. **Sessions → + สร้าง session** เลือกกล้องหน้า/หลัง และแนวตั้ง/แนวนอน
2. **ถ่าย**: วางมือถือบนขาตั้ง (กล้องเริ่มที่มุมกว้างสุด — ปุ่ม “มุมกว้าง”) แล้วทำ 2 ขั้นในการ์ด “ตั้งค่าก่อนถ่าย”
   - **Calibrate** — กล้องปรับอัตโนมัติ 2 วินาทีแล้วล็อกแสง/สี/โฟกัส (manual ถ้าเครื่องรองรับ, ไม่งั้น AE/AWB lock)
   - **บันทึกท่ากลาง** — นั่งท่ากลาง ปากปิด เห็นไหล่สองข้าง
3. **กดปุ่มอัด (วงกลมแดง) ครั้งเดียว แล้วทำท่าไปเรื่อยๆ**
   - ระหว่างท่า: กลับท่ากลางค้าง ~1 วินาที (กรอบจอเป็น **สีเขียว** = กำลังเก็บท่า)
   - แอปตัดท่าที่ใช้ได้เอง เมื่อ: ออกจากท่ากลาง → ทำท่า `clip_min_sec`–`clip_max_sec` วินาที → กลับท่ากลาง,
     ท่าต่างจากท่ากลางจริง, ไม่มีมือบังปาก/ปากเปิด/หน้าหาย
   - ทุกครั้งที่เจอ: แจ้ง **“✓ พบท่าที่ n”** + สั่น และนับที่มุมขวาบน; ท่าที่ใช้ไม่ได้แจ้งเหตุผล
   - **กดหยุดเมื่อได้ท่าพอใจ**
4. หลังหยุด แอปตัดคลิปจริงจากวิดีโอด้วย Media3 Transformer: trim แม่นระดับเฟรม, ตัดเสียง, H.264,
   ลดเฟรมเป็น 25 fps, keyframe ทุก 1 วินาที → `clips/clip_###.mp4`
5. **คลัง**: thumbnail, สถานะ, เหตุผล, แตะเพื่อเล่นวน, ลบ

ไฟล์อยู่ที่ `Android/data/com.dtrader.clipcapture/files/sessions/<id>/` (`raw/take_*.mp4`, `clips/clip_###.mp4`,
`reference.png`, `reference.json`)

ถ้าแอปปิดตัว: เปิดใหม่จะมีหน้าต่าง **“แอปปิดตัวครั้งก่อน”** พร้อมสาเหตุ — กดคัดลอกแล้วส่งให้ผู้พัฒนา

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
