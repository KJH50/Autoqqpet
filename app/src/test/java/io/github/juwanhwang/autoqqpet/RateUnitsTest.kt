package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策 rate 的单位口径（2026-11-24 修）：补给成本是“每趟”的绝对量，rate 是“每小时”，
 * 必须先把成本从每趟收益里减掉再折算到小时。同一时间收益密度的长短档应当算出同一个 rate，
 * 而不是长档被重复罚、短档被少罚——旧写法拿每趟成本直接减每小时收益，正是这个错。
 */
class RateUnitsTest {

    private fun course(name: String, gain: Int, min: Int, st: Int, cl: Int, fee: Int = 100) =
        CourseInfo(name, min.toLong(), Attr.STRENGTH, gain, fee, st, cl, min, min * 60L, true)

    private fun job(name: String, points: Int, min: Int, st: Int, cl: Int, income: Int = 0) =
        JobInfo(name, 1, min.toLong(), income, income > 0, points, st, cl, min, min * 60L, points, true)

    @Test fun sameDensityAcrossDurationsGivesSameCourseRate() {
        val p = DayPlanner(Attr.STRENGTH)
        // 都是 12 等效属性/小时：30 分钟 +6，600 分钟 +120；补给按同样比例给（10/4 与 200/80）
        val short = course("短课", 6, 30, 10, 4)
        val long = course("长课", 120, 600, 200, 80)
        // 每趟净收益：6-1.16=4.84 → 9.68/h；120-23.2=96.8 → 96.8*0.1=9.68/h
        assertEquals(9.68, p.courseRate(short), 1e-9)
        assertEquals(9.68, p.courseRate(long), 1e-9)
        assertEquals(p.courseRate(short), p.courseRate(long), 1e-9)
    }

    @Test fun sameDensityAcrossDurationsGivesSameJobRate() {
        val p = DayPlanner(Attr.STRENGTH)
        // 工分 0.2 属性/点：10 分钟 10 点 = 12/h，60 分钟 60 点 = 12/h；补给同样成比例
        val short = job("短工", 10, 10, 10, 4)
        val long = job("长工", 60, 60, 60, 24)
        assertEquals(5.04, p.jobRate(short), 1e-9)
        assertEquals(5.04, p.jobRate(long), 1e-9)
    }

    @Test fun adventureCostIsDeductedPerTripNotPerHour() {
        val p = DayPlanner(Attr.STRENGTH)
        // 45 秒短冒险：基线 0.5/趟，扣掉一整套饼干+香皂（1.16）后是负收益 —— 不该被当成 40/h 的高价值
        val costly = AdventureInfo("附近走走", 0, 0, false, Attr.NONE, 0, 0, 10, 4, 1, 45, true)
        assertTrue(p.adventureRate(costly) < 0.0)
        // 每趟成本不随趟数摊薄：负收益必须按“每趟”扣，而不是按小时摊一次
        assertEquals((0.5 - 1.16) * 60.0 / 0.75, p.adventureRate(costly), 1e-9)
    }
}
