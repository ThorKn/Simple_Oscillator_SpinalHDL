package synth.oscillator

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class OscGeneratorsSim extends AnyFunSuite {
  test("Waveform Generator mathematical verification") {
    SimConfig.compile(new OscGenerators).doSim { dut =>
      
      // Helper to check standard waveforms at a specific phase
      def checkStandard(phase: Long, expectedSaw: Int, expectedSquare: Int, expectedTri: Int, label: String): Unit = {
        dut.io.phase #= phase
        sleep(1) // Combinational settling
        
        val saw = dut.io.waves.saw.toInt
        val square = dut.io.waves.square.toInt
        val tri = dut.io.waves.tri.toInt
        
        println(f"[$label%-15s] Phase: 0x$phase%06X | Saw: $saw%6d | Sq: $square%6d | Tri: $tri%6d")
        assert(saw == expectedSaw, s"$label Saw mismatch")
        assert(square == expectedSquare, s"$label Square mismatch")
        assert(tri == expectedTri, s"$label Tri mismatch")
      }

      println("Verifying Waveform Peaks, Crossings, and Boundaries:")
      
      // Test Case 1: Saw, Square, and Triangle Peak / Phase Checks
      // Phase 0: Start of cycle
      checkStandard(0x000000, -32768, -32768, -32768, "Start (Phase 0)")

      // Quarter Cycle (0x400000)
      checkStandard(0x400000, -16384, -32768, 0, "Quarter Cycle")

      // Just before half-cycle boundary
      checkStandard(0x7FFFFF, -1, -32768, 32767, "Pre-Half Cycle")

      // Half Cycle (0x800000) - Square toggles to positive
      checkStandard(0x800000, 0, 32767, 32767, "Half Cycle")

      // Three-Quarter Cycle (0xC00000)
      checkStandard(0xC00000, 16384, 32767, -1, "3/4 Cycle")

      // Wrap-around Boundary (0xFFFFFF)
      checkStandard(0xFFFFFF, 32767, 32767, -32768, "Wrap Limit")

      // Test Case 2: PWM Duty Cycle Verification
      println("\nVerifying PWM Duty Cycle thresholds:")

      def checkPwm(pwmWidth: Int, phase: Long, expectedPwm: Int, label: String): Unit = {
        dut.io.pwmWidth #= pwmWidth
        dut.io.phase #= phase
        sleep(1)
        val pwm = dut.io.waves.pwm.toInt
        println(f"[$label%-22s] Width: 0x$pwmWidth%02X | Phase: 0x$phase%06X | PWM: $pwm%6d")
        assert(pwm == expectedPwm, s"$label PWM mismatch")
      }

      // Width = 0x80 (50% Duty Cycle threshold at 0x800000)
      checkPwm(0x80, 0x7FFFFF, 32767, "50% Duty - Pre-Thresh")
      checkPwm(0x80, 0x800000, -32768, "50% Duty - Post-Thresh")

      // Width = 0x40 (25% Duty Cycle threshold at 0x400000)
      checkPwm(0x40, 0x3FFFFF, 32767, "25% Duty - Pre-Thresh")
      checkPwm(0x40, 0x400000, -32768, "25% Duty - Post-Thresh")

      println("Waveform Generator mathematical verification successful.")
    }
  }
}
