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
      
      // 1. Initialize clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)
      
      // ---------------------------------------------------------------------
      // 1.2.1 Reset Defaults (Tested during initial startup reset)
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeAccumulator Reset Defaults:")
      
      // Set inputs to safe defaults under reset
      dut.io.resetAccum #= false
      dut.io.runAccum #= false
      dut.io.accumDir #= false
      dut.io.phaseInc #= 0

      // Wait 1 clock cycle (within startup reset window)
      dut.clockDomain.waitSampling()
      
      // Verify outputs remain strictly quiet/zero during active reset
      assert(dut.io.baseIndex.toInt == 0, s"baseIndex must remain 0 during reset, got ${dut.io.baseIndex.toInt}")
      assert(dut.io.fraction.toInt == 0, s"fraction must remain 0 during reset, got ${dut.io.fraction.toInt}")
      assert(!dut.io.segmentDone.toBoolean, s"segmentDone must remain False during reset")

      // Wait for startup reset to be fully deasserted by background thread
      dut.clockDomain.waitSampling(20)
      println("EnvelopeAccumulator Reset Defaults verified successfully.")

      // ---------------------------------------------------------------------
      // 1.2.2 Phase Accumulation & Splitting Precision
      // ---------------------------------------------------------------------
      println("Verifying Phase Accumulation and Split Bit-Mapping:")
      
      // Set phaseInc to 0x200000 (which increases accum by 2^21 per cycle)
      dut.io.runAccum #= true
      dut.io.phaseInc #= 0x200000
      
      // Cycle 1 (after 2 cycles of accumulation due to delta-cycle wait): accum = 0x200000 -> baseIndex = 0, fraction = 0
      dut.clockDomain.waitSampling(2)
      println(s"Debug Cycle 1 - accum: ${dut.accum.toLong}, baseIndex: ${dut.io.baseIndex.toInt}, fraction: ${dut.io.fraction.toInt}, resetSim: ${dut.clockDomain.resetSim.toBoolean}, runAccum: ${dut.io.runAccum.toBoolean}, phaseInc: ${dut.io.phaseInc.toInt}, resetAccum: ${dut.io.resetAccum.toBoolean}, accumDir: ${dut.io.accumDir.toBoolean}")
      assert(dut.io.baseIndex.toInt == 0, s"Expected baseIndex 0, got ${dut.io.baseIndex.toInt}")
      assert(dut.io.fraction.toInt == 0, s"Expected fraction 0, got ${dut.io.fraction.toInt}")

      // Cycle 2: accum increases to 0x400000 -> baseIndex = 0, fraction = 1
      dut.clockDomain.waitSampling()
      assert(dut.io.baseIndex.toInt == 0, s"Expected baseIndex 0, got ${dut.io.baseIndex.toInt}")
      assert(dut.io.fraction.toInt == 1, s"Expected fraction 1, got ${dut.io.fraction.toInt}")

      // Cycle 3: accum increases to 0x600000 -> baseIndex = 0, fraction = 1
      dut.clockDomain.waitSampling()
      assert(dut.io.baseIndex.toInt == 0, s"Expected baseIndex 0, got ${dut.io.baseIndex.toInt}")
      assert(dut.io.fraction.toInt == 1, s"Expected fraction 1, got ${dut.io.fraction.toInt}")

      // Cycle 4: accum increases to 0x800000 -> baseIndex = 0, fraction = 2
      dut.clockDomain.waitSampling()
      assert(dut.io.baseIndex.toInt == 0, s"Expected baseIndex 0, got ${dut.io.baseIndex.toInt}")
      assert(dut.io.fraction.toInt == 2, s"Expected fraction 2, got ${dut.io.fraction.toInt}")
      
      // Verify fraction bits tracking with smaller increment
      // Set phaseInc to 0x200000 (which increases accum by 2^21 per cycle, so 2 cycles increase fraction by 1)
      dut.io.phaseInc #= 0x200000
      dut.io.resetAccum #= true
      dut.clockDomain.waitSampling(2)
      dut.io.resetAccum #= false
      dut.clockDomain.waitSampling() // Let the resetAccum=false input be sampled so the register is ready to accumulate
      
      for (i <- 1 to 3) {
        dut.clockDomain.waitSampling(2)
        val actualFraction = dut.io.fraction.toInt
        println(s"[Fraction Cycle $i] accum: ${dut.accum.toLong}, fraction: $actualFraction, runAccum: ${dut.io.runAccum.toBoolean}, phaseInc: ${dut.io.phaseInc.toInt}, resetAccum: ${dut.io.resetAccum.toBoolean}")
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
      
      // Set phaseInc to 0x200000 (takes exactly 2048 steps to wrap)
      dut.io.phaseInc #= 0x200000
      dut.io.accumDir #= false // Forward/Up
      dut.io.runAccum #= true
      
      // Let it run until segmentDone is asserted
      var cycles = 0
      while(!dut.io.segmentDone.toBoolean && cycles < 2500) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      
      assert(dut.io.segmentDone.toBoolean, s"Expected segmentDone to assert on forward wrap-around overflow, took $cycles cycles")
      assert(cycles == 2048, s"Expected exactly 2048 cycles to overflow, got $cycles")
      
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
      
      // Set Reverse (accumDir = True), runAccum = True, phaseInc = 0x200000
      // Counting down from 0x00000000 immediately underflows on the first cycle
      dut.io.accumDir #= true
      dut.io.phaseInc #= 0x200000
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
