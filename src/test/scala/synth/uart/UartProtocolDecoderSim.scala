package synth.uart

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UartProtocolDecoderSim extends AnyFunSuite {
  test("UartProtocolDecoder valid framing and delay tolerance") {
    SimConfig.withWave.compile(new UartProtocolDecoder).doSim { dut =>
      // 1. Fork the clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // 1.3.1 Verify Reset Safety
      println("Verifying Reset Safety:")
      // Initialize inputs safely to false to prevent time-0 race conditions
      dut.io.rxByte.valid #= false
      dut.io.rxByte.payload #= 0
      
      // Explicitly assert reset and drive active inputs
      dut.clockDomain.assertReset()
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x01
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      assert(dut.io.regWrite.valid.toBoolean == false, "regWrite must remain invalid during active reset")
      
      // Clear inputs FIRST to prevent delta-cycle FSM transition races
      dut.io.rxByte.valid #= false
      dut.io.rxByte.payload #= 0
      
      // Deassert reset safely after inputs are cleared
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(100)
      println("Reset Safety verified successfully.")

      // 1.3.2 Verify Valid Command Framing (3-Byte Stream)
      println("Verifying Valid Command Framing (Back-to-Back):")
      
      // Byte 1: WriteRegister Command (0x01)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x01
      dut.clockDomain.waitSampling()
      dut.io.rxByte.valid #= false
      assert(dut.io.regWrite.valid.toBoolean == false)

      // Byte 2: FREQ_HIGH Address (0x17)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x17
      dut.clockDomain.waitSampling()
      dut.io.rxByte.valid #= false
      assert(dut.io.regWrite.valid.toBoolean == false)

      // Byte 3: Payload Data (0xAB)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0xAB
      dut.clockDomain.waitSampling() // Cycle 1: Latch 3rd byte, evaluating outputs in FSM
      dut.io.rxByte.valid #= false
      
      // Call waitSampling one more cycle to let outputs propagate and stabilize
      dut.clockDomain.waitSampling() // Cycle 2: Registered outputs are stable and visible
      
      // Verify outputs are stable and correct
      assert(dut.io.regWrite.valid.toBoolean == true, "Expected regWrite valid to be True after 3rd byte")
      assert(dut.io.regWrite.payload.address.toInt == 0x17, s"Expected address 0x17, got 0x${dut.io.regWrite.payload.address.toInt.toHexString}")
      assert(dut.io.regWrite.payload.data.toInt == 0xAB, s"Expected data 0xAB, got 0x${dut.io.regWrite.payload.data.toInt.toHexString}")

      // Next clock cycle: valid signal must automatically drop back to False
      dut.clockDomain.waitSampling()
      assert(dut.io.regWrite.valid.toBoolean == false, "Expected regWrite valid to automatically drop back to False")
      println("Valid Command Framing verified successfully.")

      // 1.3.3 Verify Byte-Spacing Delay Tolerance
      println("Verifying Byte-Spacing Delay Tolerance:")
      
      // Push Byte 1 (0x01)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x01
      dut.clockDomain.waitSampling()
      dut.io.rxByte.valid #= false
      assert(dut.io.regWrite.valid.toBoolean == false)
      
      // Wait an arbitrary spacing of 25 clock cycles
      dut.clockDomain.waitSampling(25)
      
      // Push Byte 2 (0x1A - VOLUME)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x1A
      dut.clockDomain.waitSampling()
      dut.io.rxByte.valid #= false
      assert(dut.io.regWrite.valid.toBoolean == false)
      
      // Wait an arbitrary spacing of 50 clock cycles
      dut.clockDomain.waitSampling(50)
      
      // Push Byte 3 (0x7F)
      dut.io.rxByte.valid #= true
      dut.io.rxByte.payload #= 0x7F
      dut.clockDomain.waitSampling()
      dut.io.rxByte.valid #= false
      
      // Call waitSampling one more cycle to let outputs propagate and stabilize
      dut.clockDomain.waitSampling()
      
      // Verify FSM correctly synchronized and committed the transaction
      assert(dut.io.regWrite.valid.toBoolean == true, "Expected valid transaction after arbitrary timing delays")
      assert(dut.io.regWrite.payload.address.toInt == 0x1A, "Expected address 0x1A")
      assert(dut.io.regWrite.payload.data.toInt == 0x7F, "Expected data 0x7F")
      
      // Next clock cycle: valid signal must automatically drop back to False
      dut.clockDomain.waitSampling()
      assert(dut.io.regWrite.valid.toBoolean == false)
      println("Byte-Spacing Delay Tolerance verified successfully.")
    }
  }
}
