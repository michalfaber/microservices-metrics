package simulation

import com.fasterxml.uuid.{EthernetAddress, Generators}

object UUIDGenerator {

  private val generator = {
    val nodeId = "00:11:22:33:44"
    val appId = "10"
    val macAddress = EthernetAddress.valueOf(s"$nodeId:$appId")
    Generators.timeBasedGenerator(macAddress)
  }

  def generate: String = {
    generator.generate().toString
  }
}