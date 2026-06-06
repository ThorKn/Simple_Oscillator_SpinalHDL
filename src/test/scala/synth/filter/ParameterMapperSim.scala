package synth.filter

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class ParameterMapperSim extends AnyFunSuite {
  test("ParameterMapper unit test - lookup ROM curves") {
    SimConfig.withWave.compile(new ParameterMapper).doSim { dut =>
      // Initialize clock and reset
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.cutoff #= 0
      dut.io.resonance #= 0
      dut.clockDomain.waitSampling()

      // 1. Exponential Cutoff ROM check
      println("Verifying Cutoff ROM:")
      val cutoffTestPoints = Array(0, 64, 128, 192, 255)
      for (p <- cutoffTestPoints) {
        dut.io.cutoff #= p
        dut.clockDomain.waitSampling(2)
        val outCoeff = dut.io.cutoffCoeff.toInt
        val expected = Math.round(10.0 * Math.pow(4095.0 / 10.0, p / 255.0)).toInt
        println(s"Cutoff: $p -> Coeff: $outCoeff (Expected: $expected)")
        assert(outCoeff == expected, s"Cutoff mapping failed for input $p")
      }

      // 2. Quadratic Resonance ROM check
      println("Verifying Resonance ROM:")
      val resonanceTestPoints = Array(0, 64, 128, 192, 255)
      for (r <- resonanceTestPoints) {
        dut.io.resonance #= r
        dut.clockDomain.waitSampling(2)
        val outCoeff = dut.io.resonanceCoeff.toInt
        val expected = Math.round(255.0 - 251.0 * Math.pow(r / 255.0, 2.0)).toInt
        println(s"Resonance: $r -> Coeff: $outCoeff (Expected: $expected)")
        assert(outCoeff == expected, s"Resonance mapping failed for input $r")
      }
    }
  }
}
