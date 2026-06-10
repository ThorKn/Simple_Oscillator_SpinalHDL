package synth.filter

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import synth.common.FilterConfig

class SVFSim extends AnyFunSuite {
  test("SVF integration test - flow synchronization, enable/disable, and response curves") {
    SimConfig.withWave.compile(new SVF).doSim { dut =>
      // Initialize clock domain
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      dut.io.phaseTick #= false
      dut.io.config.ctrl #= 1
      dut.io.config.mode #= 0
      dut.io.config.cutoff #= 128
      dut.io.config.resonance #= 0
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0

      // Wait 1 clock cycle to stabilize signals under active reset
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 0. Integration Reset Behavior
      // ---------------------------------------------------------------------
      println("Verifying Integration Reset Behavior:")
      // During reset (which is active at the start of forkStimulus), verify outputs are quiet
      assert(!dut.io.sampleOut.valid.toBoolean, "sampleOut.valid must be False during reset")

      assert(dut.io.sampleOut.payload.toInt == 0, "sampleOut.payload must be 0 during reset")

      // Wait for reset to complete
      dut.clockDomain.waitSampling(25)
      
      // Verify outputs remain quiet prior to active inputs/ticks
      assert(!dut.io.sampleOut.valid.toBoolean, "sampleOut.valid must remain False after reset release")
      assert(dut.io.sampleOut.payload.toInt == 0, "sampleOut.payload must remain 0 after reset release")
      println("Integration Reset Behavior verified successfully.")

      // ---------------------------------------------------------------------
      // 1. Next-phaseTick Output Flow Synchronization Timing
      // ---------------------------------------------------------------------

      println("Verifying Next-phaseTick Output Flow Synchronization Timing:")
      dut.io.config.ctrl #= 0
      dut.io.config.mode #= 0 // LP mode
      dut.io.config.cutoff #= 128
      dut.io.config.resonance #= 0
      dut.clockDomain.waitSampling()

      // Start of period (t = 0): Assert phaseTick and valid input sample
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 1000
      dut.clockDomain.waitSampling()

      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0

      // Step clock cycles and verify sampleOut.valid asserts exactly at next phaseTick (t = 50 cycles later)
      var cyclesToValid = 0
      var observedValid = false
      for (i <- 1 to 100) {
        if (dut.io.sampleOut.valid.toBoolean) {
          observedValid = true
          cyclesToValid = i
        }
        
        // At t = 49 (which corresponds to cycle 50), the next phaseTick arrives
        if (i == 49) {
          dut.io.phaseTick #= true
        } else {
          dut.io.phaseTick #= false
        }
        dut.clockDomain.waitSampling()
      }

      assert(observedValid, "sampleOut.valid was never asserted")
      assert(cyclesToValid == 50, s"Output flow valid asserted after $cyclesToValid cycles instead of 50")
      println("Output Flow Synchronization Timing verified successfully.")

      // ---------------------------------------------------------------------
      // 2. Enable / Disable Behavior
      // ---------------------------------------------------------------------
      println("Verifying Enable / Disable Behavior:")
      // When enable is False:
      // - sampleOut.payload must be 0 immediately
      // - sampleOut.valid must continue to pulse in sync with the next phaseTick boundary
      dut.io.config.ctrl #= 1
      dut.clockDomain.waitSampling()

      // Feed sample when disabled
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 1000
      dut.clockDomain.waitSampling()

      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(48) // Wait near next phaseTick

      // Next phaseTick triggers
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false

      // Verify output is gated to 0 but valid is asserted
      assert(dut.io.sampleOut.valid.toBoolean, "sampleOut.valid must pulse even when disabled")
      assert(dut.io.sampleOut.payload.toInt == 0, "sampleOut.payload must be gated to 0 when disabled")
      println("Enable / Disable behavior verified successfully.")

      // ---------------------------------------------------------------------
      // 3. Filter Frequency Response (Lowpass / Highpass Behavior)
      // ---------------------------------------------------------------------
      println("Verifying Filter Frequency Response Behavior:")
      dut.io.config.ctrl #= 0
      dut.io.config.cutoff #= 100
      dut.io.config.resonance #= 10 // Moderate resonance
      dut.clockDomain.waitSampling()

      // Helper function to process one sample through the filter and return the output payload
      def filterSample(sampleVal: Int): Int = {
        dut.io.phaseTick #= true
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sampleVal
        dut.clockDomain.waitSampling()
        dut.io.phaseTick #= false
        dut.io.sampleIn.valid #= false
        
        // Wait 49 cycles for the output sample to become valid (at next phaseTick boundary)
        for (i <- 1 to 49) {
          if (i == 49) {
            dut.io.phaseTick #= true // Setup phaseTick for next cycle
          }
          dut.clockDomain.waitSampling()
        }
        dut.io.sampleOut.payload.toInt
      }

      // --- 3.1 Lowpass Mode ---
      dut.io.config.mode #= 0
      dut.clockDomain.waitSampling()

      // Feed DC step input (low frequency)
      var lpOutputLowFreq = 0
      for (_ <- 0 until 300) {
        lpOutputLowFreq = filterSample(5000)
      }
      // Feed high-frequency toggle (Nyquist: +5000, -5000, +5000...)
      var lpOutputHighFreq = 0
      for (i <- 0 until 300) {
        val sample = if (i % 2 == 0) 5000 else -5000
        lpOutputHighFreq = filterSample(sample)
      }
      
      println(s"Lowpass Mode Output -> Low Freq: $lpOutputLowFreq, High Freq: $lpOutputHighFreq")
      // Assert that high frequency signal is significantly more attenuated than the low frequency signal
      assert(Math.abs(lpOutputLowFreq) > Math.abs(lpOutputHighFreq) * 5, "Lowpass response failed to attenuate high frequencies")

      // --- 3.2 Highpass Mode ---
      dut.io.config.mode #= 2
      dut.clockDomain.waitSampling()
      
      // Feed DC step input (should be blocked)
      var hpOutputLowFreq = 0
      for (_ <- 0 until 300) {
        hpOutputLowFreq = filterSample(5000)
      }
      // Feed high-frequency toggle (should pass)
      var hpOutputHighFreq = 0
      for (i <- 0 until 300) {
        val sample = if (i % 2 == 0) 5000 else -5000
        hpOutputHighFreq = filterSample(sample)
      }

      println(s"Highpass Mode Output -> Low Freq: $hpOutputLowFreq, High Freq: $hpOutputHighFreq")
      // Assert that low frequency signal is significantly more attenuated than the high frequency signal
      assert(Math.abs(hpOutputHighFreq) > Math.abs(hpOutputLowFreq) * 5, "Highpass response failed to block low frequencies")

      println("Filter Frequency Response Behavior verified successfully.")

    }
  }

  test("SVF integration test - saturation overshoot") {
    SimConfig.withWave.compile(new SVF).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.phaseTick #= false
      dut.io.config.ctrl #= 0
      dut.io.config.mode #= 0 // LP mode
      dut.io.config.cutoff #= 128
      dut.io.config.resonance #= 0
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0

      // Wait for reset to complete
      dut.clockDomain.waitSampling(25)

      // Helper function to process one sample through the filter and return the output payload
      def filterSample(sampleVal: Int): Int = {
        dut.io.phaseTick #= true
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sampleVal
        dut.clockDomain.waitSampling()
        dut.io.phaseTick #= false
        dut.io.sampleIn.valid #= false
        
        // Wait 49 cycles for the output sample to become valid (at next phaseTick boundary)
        for (i <- 1 to 49) {
          if (i == 49) {
            dut.io.phaseTick #= true
          }
          dut.clockDomain.waitSampling()
        }
        dut.io.sampleOut.payload.toInt
      }

      // Feed a large full-scale step input that is known to produce peaking/overshoot.
      // E.g., repeatedly feed +32767.
      // Without saturation, this would eventually wrap around and produce a negative output.
      // With saturation, it should saturate and stay clamped at 32767.
      var outputVal = 0
      for (i <- 0 until 50) {
        outputVal = filterSample(32767)
        // Verify output is positive and never wraps to a large negative number
        assert(outputVal >= 0, s"Output wrapped around to negative: $outputVal at sample $i")
      }
      assert(outputVal == 32767, s"Output did not saturate at 32767: got $outputVal")
    }
  }
}
