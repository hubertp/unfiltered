package unfiltered.oauth2

import unfiltered.request._
import unfiltered.response._
import unfiltered.filter.request.ContextPath // work on removing this dep

/** MAC request signing as defined by
 *  http://tools.ietf.org/html/draft-hammer-oauth-v2-mac-token-05
 *  The MAC protocol defines
 *  1. MAC key identifier (access token value)
 *  2. MAC key (access token secret)
 *  3. MAC algorithm - one of ("hmac-sha-1" or "hmac-sha-256")
 *  4. Issue time - time when credentials were issued to calculate the age
 */
object Mac {
  import org.apache.commons.codec.binary.Base64.encodeBase64

  val HmacSha1 = "HmacSHA1"
  val HmacSha256 = "HmacSHA256"
  val charset = "utf8"
  val MacAlgorithms = Map("hmac-sha-1" -> HmacSha1, "hmac-sha-256" -> HmacSha256)
  private val JAlgorithms = Map(HmacSha1 -> "SHA-1", HmacSha256 -> "SHA-256")

  implicit def s2b(s: String) = s.getBytes(charset)

  /** @return Either[String error, String hashed value] */
  def hash(data: Array[Byte])(algo: String) =
    JAlgorithms.get(algo) match {
      case Some(h) =>
        val msg = java.security.MessageDigest.getInstance(h)
        msg.update(data)
        Right(msg.digest)
      case unsup => Left("unsupported algorithm %s" format unsup)
    }

  /** @return Either[String error, String hashed value] */
  def macHash(alg: String, key: String)(body: String) =
    if(MacAlgorithms.isDefinedAt(alg)) {
       val macAlg = MacAlgorithms(alg)
       val mac = javax.crypto.Mac.getInstance(macAlg)
       mac.init(new javax.crypto.spec.SecretKeySpec(key, macAlg))
       Right(new String(mac.doFinal(body), charset))
    }
    else Left("unsupported mac algorithm %s" format alg)

  def bodyhash(body: Array[Byte])(alg: String) =
     hash(body)(alg).fold({ Left(_) }, { h => Right(new String(encodeBase64(h), charset)) })

  /** @return signed request for a given key, request, and algorithm */
  def sign[T](r: HttpRequest[T], nonce: String, ext: Option[String],
              key: String, alg: String): Either[String, String] =
    requestString(r, alg, nonce, ext).fold({ Left(_) }, { rstr =>
       sign(key, rstr, alg)
    })

  /** @return Either[String error, String mac signed req] */
  def sign(key: String, request: String, alg: String): Either[String, String] =
    macHash(alg, key)(request)

  /** calculates the normalized the request string from a request */
  def requestString[T](r: HttpRequest[T], alg: String,
                       nonce: String, ext: Option[String]):
                         Either[String, String] =
    MacAlgorithms.get(alg) match {
      case None => Left("unsupported mac algorithm %s" format alg)
      case Some(macAlg) =>
        r match {
          case HostPort(hostname, port) & r =>
            val body = Bytes(r).getOrElse((Array[Byte](), r))._1
            bodyhash(body)(macAlg).fold({ Left(_) }, { bhash =>
               Right(requestString(nonce, r.method, r.uri,
                          hostname, port,
                          bhash,
                          ext.getOrElse("")))
            })
       }
    }

  /** calculates the normalized request string from parts of a request */
  def requestString(nonce: String, method: String, uri: String, hostname: String,
                    port: Int, bodyhash: String, ext: String): String =
                      nonce :: method :: uri :: hostname :: port ::
                      bodyhash :: ext :: Nil mkString("\n")
}
