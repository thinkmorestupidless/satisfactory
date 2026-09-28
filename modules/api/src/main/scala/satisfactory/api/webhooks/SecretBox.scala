package satisfactory.api.webhooks

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/**
 * Webhook signing secrets at rest: AES-GCM under a key derived from `satisfactory.secret-key`. The
 * service must be able to sign, so the secret is encrypted, not hashed.
 */
final class SecretBox(secretKey: String):
  private val key    = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes(UTF_8)), "AES")
  private val random = SecureRandom()

  def seal(plaintext: String): String =
    val iv = new Array[Byte](12)
    random.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    Base64.getEncoder.encodeToString(iv ++ cipher.doFinal(plaintext.getBytes(UTF_8)))

  def open(box: String): String =
    val bytes  = Base64.getDecoder.decode(box)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.take(12)))
    String(cipher.doFinal(bytes.drop(12)), UTF_8)
