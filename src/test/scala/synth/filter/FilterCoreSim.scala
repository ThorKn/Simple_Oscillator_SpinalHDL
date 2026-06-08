package synth.filter

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FilterCoreSim extends AnyFunSuite {
  test("FilterCore unit test - sequencing, reset, and extreme overflows") {
    SimConfig.withWave.compile(new FilterCore).doSim { dut =>
      // Initialize Clock Domain
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      dut.io.phaseTick #= false
      dut.io.clear #= false
      dut.io.sampleIn #= 0
      dut.io.cutoffCoeff #= 0
      dut.io.resonanceCoeff #= 0
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 1. ClockDomain Reset Behavior
      // ---------------------------------------------------------------------
      println("Verifying ClockDomain Reset Behavior:")
      // Assert that while reset is active, state outputs are 0
      assert(dut.io.lp.toInt == 0, "lp must be 0 during reset")
      assert(dut.io.bp.toInt == 0, "bp must be 0 during reset")
      assert(dut.io.hp.toInt == 0, "hp must be 0 during reset")
      assert(!dut.io.done.toBoolean, "done must be False during reset")

      // Wait for reset to deassert (20 cycles)
      dut.clockDomain.waitSampling(20)
      assert(!dut.io.done.toBoolean, "done must remain False after reset release before phaseTick")

      // ---------------------------------------------------------------------
      // 2. State Reset (Clear)
      // ---------------------------------------------------------------------
      println("Verifying State Reset (Clear):")
      // Assert that when clear is asserted, internal states remain zero
      dut.io.clear #= true
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(10)

      assert(dut.io.lp.toInt == 0, "lp must be 0 when clear is active")
      assert(dut.io.bp.toInt == 0, "bp must be 0 when clear is active")
      dut.io.clear #= false
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 3. FSM Sequencing & Timing
      // ---------------------------------------------------------------------
      println("Verifying FSM Sequencing & Timing:")
      // Assert that done asserts exactly on cycle 8 after phaseTick
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false

      var cycleCount = 0
      var doneAsserted = false
      while (cycleCount < 15 && !doneAsserted) {
        if (dut.io.done.toBoolean) {
          doneAsserted = true
        } else {
          cycleCount += 1
          dut.clockDomain.waitSampling()
        }
      }
      // FSM starts IDLE, and runs CALC_RES, SUB_INPUT, CALC_HP, CALC_BP_TERM, UPDATE_BP, CALC_LP_TERM, UPDATE_LP.
      // So calculation takes 7 cycles after phaseTick, done is asserted in the 8th cycle (UPDATE_LP).
      assert(doneAsserted, "done signal was never asserted")
      assert(cycleCount == 7, s"FSM execution took $cycleCount cycles instead of 7 cycles (done at cycle 8)")

      // ---------------------------------------------------------------------
      // 4. Arithmetic Execution (Normal Vectors)
      // ---------------------------------------------------------------------
      println("Verifying Arithmetic Execution with Normal Vectors:")
      // Reset state and coefficients
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      dut.clockDomain.waitSampling()

      // Set input sample = 4000, cutoffCoeff = 2048 (0.5), resonanceCoeff = 128 (0.5)
      dut.io.sampleIn #= 4000
      dut.io.cutoffCoeff #= 2048
      dut.io.resonanceCoeff #= 128

      // Trigger calculation
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false

      // Wait for done to assert
      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }

      // Expected calculation:
      // lp = 0, bp = 0, input = 4000
      // resTerm = (0 * 128) >> 8 = 0
      // tempSub = 4000 - 0 = 4000
      // hp = 4000 - 0 = 4000
      // bpTerm = (4000 * 2048) >> 12 = 2000
      // bpNext = 0 + 2000 = 2000
      // lpTerm = (2000 * 2048) >> 12 = 1000
      // lpNext = 0 + 1000 = 1000
      dut.clockDomain.waitSampling() // Let the state updates register
      assert(dut.io.lp.toInt == 1000, s"LP output failed: expected 1000, got ${dut.io.lp.toInt}")
      assert(dut.io.bp.toInt == 2000, s"BP output failed: expected 2000, got ${dut.io.bp.toInt}")
      assert(dut.io.hp.toInt == 4000, s"HP output failed: expected 4000, got ${dut.io.hp.toInt}")

      // ---------------------------------------------------------------------
      // 5. Extreme Values and Overflow/Wrap-Around Verification
      // ---------------------------------------------------------------------
      println("Verifying Extreme Values and Overflow/Wrap-Around:")

      // We manually verify overflow of 24-bit state registers.
      // E.g. If lp = -8388608 (min negative 24-bit value) and sampleIn = 32767.
      // Then tempSub = sampleIn - lp = 32767 - (-8388608) = 8421375.
      // This overflows 24-bit signed range (max = 8388607).
      // Wrap-around: 8421375 - 16777216 = -8355841.
      // Wait! Let's check: 8421375 = 0x807FFF.
      // As a 24-bit signed integer, 0x807FFF has the MSB (bit 23) set, so it represents:
      // 0x807FFF - 0x1000000 = 8421375 - 16777216 = -8355841.
      // So it wraps to -8355841.
      
      // Let's set the FSM state registers to force this test vector.
      // We force the internal registers (which we will name lpReg and bpReg in FilterCore)
      // while the FSM is in IDLE.
      dut.lpReg #= -8388608
      dut.bpReg #= 0
      dut.io.sampleIn #= 32767
      dut.io.cutoffCoeff #= 0
      dut.io.resonanceCoeff #= 0
      dut.clockDomain.waitSampling()

      // Trigger calculation
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false

      // Wait for done to assert
      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling() // Let states update

      // tempSub = 32767 - (-8388608) = 8421375.
      // 8421375 exceeds 24-bit signed max (8388607) and wraps around to -8355841.
      // Since resonanceCoeff = 0, resTerm = 0, so hp = tempSub = -8355841.
      assert(dut.io.hp.toInt == -8355841, s"HP overflow wrap failed: expected -8355841, got ${dut.io.hp.toInt}")

      // 5.2 Negative Limit Multiplier Stability
      println("Verifying Negative Limit Multiplier Stability:")
      // Reset filter states
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      dut.clockDomain.waitSampling()

      // Set bpReg to minimum negative SInt: -8388608
      // Set resonanceCoeff to maximum: 255
      dut.bpReg #= -8388608
      dut.io.sampleIn #= 0
      dut.io.resonanceCoeff #= 255
      dut.io.cutoffCoeff #= 0
      dut.clockDomain.waitSampling()

      // Trigger calculation
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling()

      // bp = -8388608, resonanceCoeff = 255.
      // product = -8388608 * 255 = -2139095040 (SInt 33-bit).
      // shifted by 8: -2139095040 >> 8 = -8355840.
      // This is a negative value. We verify it does not corrupt the sign bit.
      // hp = 0 - lp - resTerm = 0 - 0 - (-8355840) = 8355840.
      assert(dut.io.hp.toInt == 8355840, s"Negative limit multiplier failed: expected 8355840, got ${dut.io.hp.toInt}")

    }
  }
}
