package synth.filter

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FilterMuxSim extends AnyFunSuite {
  test("FilterMux unit test - mode selection and resizing") {
    SimConfig.withWave.compile(new FilterMux).doSim { dut =>
      // Initialize inputs
      dut.io.mode #= 0
      dut.io.lp #= 0
      dut.io.bp #= 0
      dut.io.hp #= 0
      
      // Fork stimulus is not strictly required since it is a combinational mux, 
      // but we wait for reset phase or do it for consistency.
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling()

      // Define internal test inputs (24-bit signed)
      val lpVal = 0x123456
      val bpVal = -0x154321
      val hpVal = 0x07FFFF

      dut.io.lp #= lpVal
      dut.io.bp #= bpVal
      dut.io.hp #= hpVal
      dut.clockDomain.waitSampling()

      // Test Mode 00: Lowpass (expect saturation at positive max)
      dut.io.mode #= 0
      dut.clockDomain.waitSampling()
      val expectedLp = 32767
      assert(dut.io.sampleOut.toInt == expectedLp, s"LP mode failed: got ${dut.io.sampleOut.toInt}, expected $expectedLp")

      // Test Mode 01: Bandpass (expect saturation at negative max)
      dut.io.mode #= 1
      dut.clockDomain.waitSampling()
      val expectedBp = -32768
      assert(dut.io.sampleOut.toInt == expectedBp, s"BP mode failed: got ${dut.io.sampleOut.toInt}, expected $expectedBp")

      // Test Mode 10: Highpass (expect saturation at positive max)
      dut.io.mode #= 2
      dut.clockDomain.waitSampling()
      val expectedHp = 32767
      assert(dut.io.sampleOut.toInt == expectedHp, s"HP mode failed: got ${dut.io.sampleOut.toInt}, expected $expectedHp")
    }
  }
}
