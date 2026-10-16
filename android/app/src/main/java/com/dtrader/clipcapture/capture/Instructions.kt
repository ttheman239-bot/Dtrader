package com.dtrader.clipcapture.capture

/** Random movement cues so each clip differs (§4.3 step 2). */
object Instructions {
    private val calm = listOf(
        "เอียงหัวไปทางซ้ายช้าๆ แล้วค่อยกลับ",
        "เอียงหัวไปทางขวาช้าๆ แล้วค่อยกลับ",
        "โยกตัวไปทางซ้ายเล็กน้อย",
        "โยกตัวไปทางขวาเล็กน้อย",
        "ขยับไหล่ขึ้น-ลงเบาๆ",
        "พยักหน้าช้าๆ เหมือนกำลังฟัง",
        "หันมองทางซ้ายเล็กน้อยแล้วกลับ",
        "หันมองทางขวาเล็กน้อยแล้วกลับ",
        "หายใจลึกๆ ขยับตัวเล็กน้อย",
    )
    private val active = listOf(
        "โยกตัวซ้าย-ขวาเป็นจังหวะ",
        "ขยับไหล่และเอียงหัวพร้อมกัน",
        "พยักหน้าเหมือนเห็นด้วยกับผู้ชม",
        "เอนตัวไปข้างหน้าแล้วกลับ",
        "หันซ้าย-ขวาเหมือนมองผู้ชมทั้งห้อง",
        "ยักไหล่แล้วยิ้มเล็กน้อย (ปากปิด)",
        "ขยับมือเล็กน้อยระดับอก (ห้ามบังปาก)",
    )

    fun next(tag: String, previous: String?): String {
        val pool = if (tag == "active") active else calm
        return pool.filter { it != previous }.random()
    }
}
