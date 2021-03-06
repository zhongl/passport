/*
 *  Copyright 2019 Zhong Lunfu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package zhongl.passport

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.util.FastFuture
import akka.testkit.TestKit
import com.auth0.jwt.JWT
import com.typesafe.config.ConfigFactory
import org.scalatest._
import spray.json._
import zhongl.passport.Platforms._
import zhongl.stream.oauth2.{dingtalk, wechat, OAuth2}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PlatformsSpec extends TestKit(ActorSystem("platform")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val jc = JwtCookie.apply(ConfigFactory.parseString("""
                                                               |include "common.conf"
                                                               |cookie {
                                                               | domain = ".a.b"
                                                               | secret = "***"
                                                               |}
    """.stripMargin))

  "Platforms" should {
    "load dingtalk" in {
      Platforms.bound(ConfigFactory.parseString("dingtalk.mobile.appid = 1")) shouldBe Platforms.ding
    }

    "load wechat" in {
      Platforms.bound(ConfigFactory.parseString("wechat.corp = 1")) shouldBe Platforms.wework
    }

    "load first" in {
      Platforms.bound(ConfigFactory.parseString("dingtalk.mobile.appid = 1 \nwechat.corp = 1")) shouldBe Platforms.ding
    }

    "complain no platform has bound" in {
      intercept[IllegalStateException](
        Platforms.bound(ConfigFactory.empty())
      ).getMessage shouldBe "Either [dingtalk] or [wechat] should be configured."
    }

    "have ding" in {
      val jsonSupport     = new dingtalk.JsonSupport {}
      import jsonSupport._
      val info            = dingtalk.UserInfo("1", "n", "e", Seq(1), "a", true, Seq.empty)
      val signature       = Platforms.ding.builder(info).sign(jc.algorithm)
      val maybeDecodedJWT = jc.unapply(HttpRequest(headers = List(Cookie(jc.name, signature))))
      maybeDecodedJWT.map(Platforms.ding.extractor).foreach(_ shouldBe info.toJson.prettyPrint)
    }

    "have wework" in {
      val jsonSupport     = new wechat.JsonSupport {}
      import jsonSupport._
      val info            = wechat.UserInfo("1", "n", Seq(1), "e", "a", 0, 0, 0, "")
      val signature       = Platforms.wework.builder(info).sign(jc.algorithm)
      val maybeDecodedJWT = jc.unapply(HttpRequest(headers = List(Cookie(jc.name, signature))))
      maybeDecodedJWT.map(Platforms.wework.extractor).foreach(_ shouldBe info.toJson.prettyPrint)
    }

    "return auto redirect html" in {
      val builder: Builder[String] = s => JWT.create().withSubject(s)
      val extractor: Extractor     = j => j.getSubject
      val p                        = new Platform[String, dingtalk.AccessToken](builder, extractor) {
        override protected def concrete(authenticated: Authenticated[String])(implicit system: ActorSystem) =
          new OAuth2[dingtalk.AccessToken] {
            override def refresh = {
              FastFuture.successful(dingtalk.AccessToken("token", 7200))
            }

            override def authenticate(token: dingtalk.AccessToken, authorized: HttpRequest) = {
              FastFuture.successful(authenticated("", Uri("http://auto.redirect")))
            }

            override def authorization(state: String) = Location(Uri())

            override def redirect = Uri()
          }
      }

      val o = p.oauth2(jc.generate)
      val t = Await.result(o.refresh, Duration.Inf)
      val f = o.authenticate(t, HttpRequest())

      val r = Await.result(f, Duration.Inf)
      r.status shouldBe StatusCodes.OK
      r.entity shouldBe HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          |  <head></head>
          |  <body>
          |    <h1><a href="http://auto.redirect">http://auto.redirect</a></h1>
          |    <script>window.location.assign("http://auto.redirect")</script>
          |  </body>
          |</html>
          |""".stripMargin
      )
    }
  }

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)
}
