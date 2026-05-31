package synth.envelope

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class EnvelopeAccumulatorSim extends AnyFunSuite {

  // =========================================================================
  // 1.2 EnvelopeAccumulator Unit Test
  // =========================================================================
  test("EnvelopeAccumulator unit test - phase counting & wrapping") {
    SimConfig.withWave.compile(new EnvelopeAccumulator).doSim { dut =>
      
      // 1. Initialize clock and assert reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)
      
      // Initialize inputs to safe defaults
      dut.io.resetAccum #= false
      dut.io.runAccum #= false
      dut.io.accumDir #= false
      dut.io.phaseInc #= 0

      // ---------------------------------------------------------------------
      // 1.2.1 Reset Defaults
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeAccumulator Reset Defaults:")
      dut.clockDomain.assertReset()
      dut.io.runAccum #= true
      dut.io.accumDir #= true
      dut.io.phaseInc #= 0x40000000
      
      // Sample for 5 clock cycles while reset is active
      for (i <- 1 to 5) {
        dut.clockDomain.waitSampling()
        assert(dut.io.baseIndex.toInt == 0, s"[Reset Default $i] baseIndex must remain 0 during reset, got ${dut.io.baseIndex.toInt}")
        assert(dut.io.fraction.toInt == 0, s"[Reset Default $i] fraction must remain 0 during reset, got ${dut.io.fraction.toInt}")
        assert(!dut.io.segmentDone.toBoolean, s"[Reset Default $i] segmentDone must remain False during reset")
      }
      
      // Clear inputs and deassert reset
      dut.io.runAccum #= false
      dut.io.accumDir #= false
      dut.io.phaseInc #= 0
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      println("EnvelopeAccumulator Reset Defaults verified successfully.")

      // ---------------------------------------------------------------------
      // 1.2.2 Phase Accumulation & Splitting Precision
      // ---------------------------------------------------------------------
      println("Verifying Phase Accumulation and Split Bit-Mapping:")
      
      // Set phaseInc to 0x10000000 (which increases the upper 8-bit baseIndex by exactly 16 each cycle)
      dut.io.runAccum #= true
      dut.io.phaseInc #= 0x10000000
      
      // Verify progressive base index steps
      for (i <- 1 to 3) {
        dut.clockDomain.waitSampling()
        val expectedIndex = i * 16
        val actualIndex = dut.io.baseIndex.toInt
        assert(actualIndex == expectedIndex, s"[Accumulation Cycle $i] Expected baseIndex $expectedIndex, got $actualIndex")
      }
      
      // Verify fraction bits tracking
      // Set phaseInc to 0x00400000 (which increases the lower 2-bit fraction by exactly 1 each cycle)
      dut.io.phaseInc #= 0x00400000
      dut.io.resetAccum #= true
      dut.clockDomain.waitSampling()
      dut.io.resetAccum #= false
      
      for (i <- 1 to 3) {
        dut.clockDomain.waitSampling()
        val actualFraction = dut.io.fraction.toInt
        assert(actualFraction == i, s"[Fraction Cycle $i] Expected fraction $i, got $actualFraction")
      }
      
      dut.io.runAccum #= false
      dut.clockDomain.waitSampling()
      println("Phase Accumulation and Split Bit-Mapping verified successfully.")

      // ---------------------------------------------------------------------
      // 1.2.3 Forward Wrap Done
      // ---------------------------------------------------------------------
      println("Verifying Forward Wrap-Around Done Detection:")
      
      // Reset accumulator to 0
      dut.io.resetAccum #= true
      dut.clockDomain.waitSampling()
      dut.io.resetAccum #= false
      
      // Set phaseInc to 0x40000000 (wraps past 32-bit bound in exactly 4 steps)
      dut.io.phaseInc #= 0x40000000
      dut.io.accumDir #= false // Forward/Up
      dut.io.runAccum #= true
      
      dut.clockDomain.waitSampling() // cycle 1 (0x40000000)
      assert(!dut.io.segmentDone.toBoolean, "segmentDone must remain False before overflow")
      dut.clockDomain.waitSampling() // cycle 2 (0x80000000)
      assert(!dut.io.segmentDone.toBoolean, "segmentDone must remain False before overflow")
      dut.clockDomain.waitSampling() // cycle 3 (0xC0000000)
      assert(!dut.io.segmentDone.toBoolean, "segmentDone must remain False before overflow")
      dut.clockDomain.waitSampling() // cycle 4 (overflow -> wraps back to 0x00000000)
      assert(dut.io.segmentDone.toBoolean, "Expected segmentDone to assert True exactly on forward wrap-around overflow")
      
      // Verify segmentDone clears automatically on subsequent cycles
      dut.clockDomain.waitSampling()
      assert(!dut.io.segmentDone.toBoolean, "segmentDone must drop back to False in subsequent cycles")
      
      dut.io.runAccum #= false
      dut.clockDomain.waitSampling()
      println("Forward Wrap-Around Done Detection verified successfully.")

      // ---------------------------------------------------------------------
      // 1.2.4 Reverse Underflow Done
      // ---------------------------------------------------------------------
      println("Verifying Reverse Underflow Done Detection:")
      
      // Reset accumulator to 0
      dut.io.resetAccum #= true
      dut.clockDomain.waitSampling()
      dut.io.resetAccum #= false
      
      // Set Reverse (accumDir = True), runAccum = True, phaseInc = 0x40000000
      // Counting down from 0x00000000 immediately underflows to 0xC0000000 on the first cycle
      dut.io.accumDir #= true
      dut.io.phaseInc #= 0x40000000
      dut.io.runAccum #= true
      
      dut.clockDomain.waitSampling()
      assert(dut.io.segmentDone.toBoolean, "Expected segmentDone to assert True instantly on reverse underflow past 0")
      
      // Verify segmentDone clears automatically on subsequent cycles
      dut.clockDomain.waitSampling()
      assert(!dut.io.segmentDone.toBoolean, "segmentDone must drop back to False in subsequent cycles")
      
      dut.io.runAccum #= false
      dut.clockDomain.waitSampling()
      println("Reverse Underflow Done Detection verified successfully.")
    }
  }
}
