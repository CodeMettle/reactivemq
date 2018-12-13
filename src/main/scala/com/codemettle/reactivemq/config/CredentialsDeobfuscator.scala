package com.codemettle.reactivemq.config

import java.nio.charset.StandardCharsets
import java.util.Base64

import akka.actor.ExtendedActorSystem

/**
  * @author steven
  *
  */
trait CredentialsDeobfuscator {
  def deobfuscateUsername(obfuscated: String): String
  def deobfuscatePassword(obfuscated: String): String
}

class PlaintextDeobfuscator(system: ExtendedActorSystem) extends CredentialsDeobfuscator {
  override def deobfuscateUsername(obfuscated: String): String = obfuscated
  override def deobfuscatePassword(obfuscated: String): String = obfuscated
}

class Base64Deobfuscator(system: ExtendedActorSystem) extends CredentialsDeobfuscator {
  private def decode(str: String) = new String(Base64.getDecoder.decode(str), StandardCharsets.UTF_8)

  override def deobfuscateUsername(obfuscated: String): String = decode(obfuscated)

  override def deobfuscatePassword(obfuscated: String): String = decode(obfuscated)
}

// this is all dumb anyway, why not get real silly with it
class Rot13Deobfuscator(system: ExtendedActorSystem) extends CredentialsDeobfuscator {
  private def rotate(str: String) = {
    def change(char: Char): Char = {
      def doRotate(char: Char, low: Char, high: Char) = {
        if (char >= low && char <= high) {
          val newC: Char = (char + 13).toChar
          if (newC > high)
            (newC - 26).toChar
          else
            newC
        } else char
      }

      doRotate(doRotate(char, 'A', 'Z'), 'a', 'z')
    }

    val sb = new StringBuilder
    str.map(change).foreach(sb += _)
    sb.toString()
  }

  override def deobfuscateUsername(obfuscated: String): String = rotate(obfuscated)

  override def deobfuscatePassword(obfuscated: String): String = rotate(obfuscated)
}
