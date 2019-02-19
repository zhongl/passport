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
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity.{Chunk, Chunked}
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._

class DynamicSpec extends WordSpec with Matchers with BeforeAndAfterAll with Directives with Docker.JsonSupport {

  private implicit val system = ActorSystem(getClass.getSimpleName)
  private implicit val mat    = ActorMaterializer()

  private val docker = Docker("tcp://localhost:12306")

  private val bound = Await.result(Http().bindAndHandle(mockDockerDaemon, "localhost", 12306), 1.second)

  "Dynamic" should {
    "by docker local" in {
      val f = Dynamic.by(docker)("docker").runWith(Sink.head)
      Await.result(f, Duration.Inf)(Host("foo.bar")) shouldBe Host("demo", 8080)
    }

    "by docker swarm" in {
      val f = Dynamic.by(docker)("swarm").runWith(Sink.head)
      Await.result(f, Duration.Inf)(Host("foo.bar")) shouldBe Host("demo", 0)
    }
  }

  def mockDockerDaemon: Route = get {
    concat(
      (path("events") & parameter("filters")) { _ =>
        complete(Chunked(ContentTypes.`application/json`, Source.repeat(Chunk(ByteString(" ")))))
      },
      (path("containers") & parameter("filters")) { _ =>
        complete(List(Docker.Container("id", List("/demo"), Map("passport.rule" -> ".+|>|:8080"))))
      },
      (path("services") & parameter("filters")) { _ =>
        complete(List(Docker.Service("id", Docker.Spec("demo", Map("passport.rule" -> ".+")))))
      }
    )
  }

  override protected def afterAll(): Unit = {
    bound.terminate(1.second)
    system.terminate()
  }
}