package synth.envelope

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class EnvelopeShaperSim extends AnyFunSuite {

  // =========================================================================
  // 1.3 EnvelopeShaper Unit Test
  // =========================================================================
  test("EnvelopeShaper unit test - curves, interpolation & parallel outputs") {
    SimConfig.withWave.compile(new EnvelopeShaper).doSim { dut =>
      
      // 1. Initialize clock and assert reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      dut.io.phaseTick #= false
      dut.io.baseIndex #= 0
      dut.io.fraction #= 0
      dut.io.curveSelect #= 0
      dut.io.sustainLevel #= 0
      dut.io.activeStage #= 0      // Wait 1 clock cycle to stabilize signals under active reset
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 1.3.1 Reset Stability
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeShaper Reset Stability:")
      dut.io.phaseTick #= true
      dut.io.baseIndex #= 128
      dut.io.curveSelect #= 1
      
      // Verify outputs remain strictly quiet during the natural active reset phase of forkStimulus
      assert(!dut.io.envelopeOut.valid.toBoolean, "envelopeOut.valid must remain False under reset")
      assert(dut.io.envelopeOut.payload.toInt == 0, "envelopeOut.payload must remain 0 under reset")
      assert(!dut.io.envelopeOutSigned.valid.toBoolean, "envelopeOutSigned.valid must remain False under reset")
      assert(dut.io.envelopeOutSigned.payload.toInt == 0, "envelopeOutSigned.payload must remain 0 under reset")
      
      // Wait for the startup reset (forkStimulus) to automatically complete (takes 20 cycles)
      dut.clockDomain.waitSampling(20)
      
      // Clear inputs
      dut.io.phaseTick #= false
      dut.io.baseIndex #= 0
      dut.io.curveSelect #= 0
      println("EnvelopeShaper Reset Stability verified successfully.")

      // ---------------------------------------------------------------------
      // 1.3.2 Multiplierless Shift-Add Interpolation Accuracy
      // ---------------------------------------------------------------------
      println("Verifying Multiplierless Shift-Add Interpolation Accuracy:")
      
      // Set to Linear Curve model (curveSelect = 00)
      // Linear ROM values are perfectly continuous: LUT[x] = x. 
      // Setting baseIndex = 10 means Y0 = LUT[10] = 10 and Y1 = LUT[11] = 11.
      dut.io.curveSelect #= 0
      dut.io.baseIndex #= 10
      dut.io.phaseTick #= true
      
      // Wait to let the new curve selection and baseIndex settle in the register pipeline
      dut.clockDomain.waitSampling(2)
      
      // We step through all 4 fractional interpolation boundaries:
      val fractions = Array(0, 1, 2, 3)
      for (f <- fractions) {
        dut.io.fraction #= f
        dut.clockDomain.waitSampling(2)
        
        // Expected value: Y = Y0 + (f/4) * (Y1-Y0)
        // Scaled to 10-bit output: (Y0 * 4 + f) = (10 * 4 + f) = (40 + f)
        val expectedVal = 40 + f
        val actualVal = dut.io.envelopeOut.payload.toInt
        println(s"DEBUG: f=$f, baseIndex=${dut.io.baseIndex.toInt}, fraction=${dut.io.fraction.toInt}, curveSelect=${dut.io.curveSelect.toInt}")
        println(s"DEBUG INTERNAL: y0=${dut.y0.toInt}, y1=${dut.y1.toInt}, finalValUnipolar=${dut.finalValUnipolar.toInt}, delayedActiveStage=${dut.delayedActiveStage.toInt}, isSustainDelayed=${dut.isSustainDelayed.toBoolean}, finalValUnipolarClamped=${dut.finalValUnipolarClamped.toInt}")
        println(s"DEBUG OUTPUT: payload=$actualVal, valid=${dut.io.envelopeOut.valid.toBoolean}")
        assert(actualVal == expectedVal, s"[Fraction f = $f] Expected interpolated output $expectedVal, got $actualVal")
      }
      
      println("Multiplierless Shift-Add Interpolation Accuracy verified successfully.")

      // ---------------------------------------------------------------------
      // 1.3.3 Parallel Bipolar Output Scaling
      // ---------------------------------------------------------------------
      println("Verifying Parallel Bipolar Output Scaling:")
      
      // We sweep across multiple arbitrary baseIndex values and verify the SInt conversion
      val testIndices = Array(0, 64, 128, 255)
      for (idx <- testIndices) {
        dut.io.baseIndex #= idx
        dut.io.fraction #= 0
        dut.clockDomain.waitSampling(2)
        
        val unipolarVal = dut.io.envelopeOut.payload.toInt
        val bipolarVal = dut.io.envelopeOutSigned.payload.toInt
        
        // Bipolar must map directly to MSB-inverted unipolar: (unipolarVal ^ 0x200) as signed 10-bit SInt
        val expectedBipolar = if ((unipolarVal ^ 0x200) >= 512) {
          (unipolarVal ^ 0x200) - 1024
        } else {
          unipolarVal ^ 0x200
        }
        
        assert(bipolarVal == expectedBipolar, s"[BaseIndex = $idx] Expected bipolar $expectedBipolar, got $bipolarVal")
      }
      
      println("Parallel Bipolar Output Scaling verified successfully.")

      // ---------------------------------------------------------------------
      // 1.3.4 Sample Gating Tick Validation
      // ---------------------------------------------------------------------
      println("Verifying Audio Sample Gating Validation:")
      
      // Pulse phaseTick High -> Flow output should be valid
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling(2)
      assert(dut.io.envelopeOut.valid.toBoolean, "envelopeOut.valid must be True when phaseTick is active")
      assert(dut.io.envelopeOutSigned.valid.toBoolean, "envelopeOutSigned.valid must be True when phaseTick is active")
      
      // Pull phaseTick Low -> Flow output must immediately drop valid
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(2)
      assert(!dut.io.envelopeOut.valid.toBoolean, "envelopeOut.valid must drop False when phaseTick is inactive")
      assert(!dut.io.envelopeOutSigned.valid.toBoolean, "envelopeOutSigned.valid must drop False when phaseTick is inactive")
      
      println("Audio Sample Gating Validation verified successfully.")
    }
  }
}

