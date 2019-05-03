package com.softwaremill.demo

import java.nio.file.Path
import java.util.UUID

object Demo extends App {

  // model
  class Year(val year: Int) extends AnyVal

  case class Country(name: String)

  case class Author(name: String, country: Country)

  case class Book(id: UUID, title: String, year: Year, author: Author)

  case class NewBook(title: String, cover: Option[Path], year: Year, authorName: String, authorCountry: String)

  case class BooksQuery(year: Option[Year], limit: Option[Int])

  case class ErrorInfo(error: String)

  type AuthToken = String

  //

  object Database {
    var books: List[Book] = List(
      Book(
        UUID.randomUUID(),
        "The Sorrows of Young Werther",
        new Year(1774),
        Author("Johann Wolfgang von Goethe", Country("Germany"))
      ),
      Book(UUID.randomUUID(), "Iliad", new Year(-8000), Author("Homer", Country("Greece"))),
      Book(UUID.randomUUID(), "Nad Niemnem", new Year(1888), Author("Eliza Orzeszkowa", Country("Poland"))),
      Book(UUID.randomUUID(), "The Colour of Magic", new Year(1983), Author("Terry Pratchett", Country("United Kingdom"))),
      Book(UUID.randomUUID(), "The Art of Computer Programming", new Year(1968), Author("Donald Knuth", Country("USA"))),
      Book(UUID.randomUUID(), "Pharaoh", new Year(1897), Author("Boleslaw Prus", Country("Poland")))
    )

    var bookCovers: Map[UUID, Path] = Map.empty
  }

  object FirstEndpoint {

    import io.circe.generic.auto._
    import tapir._
    import tapir.json.circe._
    import tapir.model.StatusCode
    import tapir.Codec.PlainCodec

    implicit val yearCodec: PlainCodec[Year] = implicitly[PlainCodec[Int]].map(new Year(_))(_.year)

    val getBooks: Endpoint[(Option[Year], Option[Int]), (StatusCode, ErrorInfo), List[Book], Nothing] = endpoint.get
      .in("api" / "v1.0" / "books")
      .in(query[Option[Year]]("year"))
      .in(query[Option[Int]]("limit"))
      .errorOut(statusCode.and(jsonBody[ErrorInfo]))
      .out(jsonBody[List[Book]])
  }

  object Endpoints {

    import io.circe.generic.auto._
    import tapir._
    import tapir.json.circe._
    import tapir.model.StatusCode
    import tapir.Codec.PlainCodec
    import akka.stream.scaladsl.Source
    import akka.util.ByteString

    implicit val yearCodec: PlainCodec[Year] = implicitly[PlainCodec[Int]].map(new Year(_))(_.year)

    val baseEndpoint: Endpoint[Unit, (StatusCode, ErrorInfo), Unit, Nothing] = endpoint
      .in("api" / "v1.0")
      .errorOut(statusCode.and(jsonBody[ErrorInfo]))

    val booksQueryInput: EndpointInput[BooksQuery] = query[Option[Year]]("year").and(query[Option[Int]]("limit")).mapTo(BooksQuery)

    val getBooks: Endpoint[BooksQuery, (StatusCode, ErrorInfo), List[Book], Nothing] = baseEndpoint.get
      .in("book")
      .in(booksQueryInput)
      .out(jsonBody[List[Book]].example(List(Database.books.head)))

    val getBookCover: Endpoint[UUID, (StatusCode, ErrorInfo), Source[ByteString, Any], Source[ByteString, Any]] = baseEndpoint.get
      .in("book" / path[UUID]("bookId") / "cover")
      .out(streamBody[Source[ByteString, Any]](schemaFor[Array[Byte]], MediaType.OctetStream()))

    val addBook: Endpoint[(AuthToken, NewBook), (StatusCode, ErrorInfo), Unit, Nothing] = baseEndpoint.post
      .in(auth.bearer)
      .in("book")
      .in(multipartBody[NewBook])

  }

  object AkkaRoutes {

    import tapir.server.akkahttp._
    import tapir.model.StatusCodes
    import Database._
    import akka.http.scaladsl.server.Route
    import akka.stream.scaladsl.FileIO
    import scala.concurrent.Future

    val getBooksRoute: Route = Endpoints.getBooks.toRoute { booksQuery =>
      if (booksQuery.limit.getOrElse(0) < 0) {
        Future.successful(Left((StatusCodes.BadRequest, ErrorInfo("Limit must be positive"))))
      } else {
        val filteredByYear = booksQuery.year.map(year => books.filter(_.year == year)).getOrElse(books)
        val limited = booksQuery.limit.map(limit => filteredByYear.take(limit)).getOrElse(filteredByYear)
        Future.successful(Right(limited))
      }
    }

    val getBookCoverRoute: Route = Endpoints.getBookCover.toRoute { bookId =>
      bookCovers.get(bookId) match {
        case None                => Future.successful(Left((StatusCodes.NotFound, ErrorInfo("Book not found"))))
        case Some(bookCoverPath) => Future.successful(Right(FileIO.fromPath(bookCoverPath)))
      }
    }

    val addBookRoute: Route = Endpoints.addBook.toRoute {
      case (authToken, newBook) =>
        if (authToken == "secret") {
          val book = Book(UUID.randomUUID(), newBook.title, newBook.year, Author(newBook.authorName, Country(newBook.authorCountry)))
          books = books :+ book
          newBook.cover.foreach { cover =>
            bookCovers = bookCovers + (book.id -> cover)
          }
          Future.successful(Right(()))
        } else {
          Future.successful(Left((StatusCodes.Unauthorized, ErrorInfo("Incorrect auth token"))))
        }
    }
  }

  object Documentation {

    import Endpoints._
    import tapir.docs.openapi._
    import tapir.openapi.circe.yaml._
    import tapir.openapi.OpenAPI

    val openApi: OpenAPI = List(getBooks, getBookCover, addBook).toOpenAPI("The tapir library", "0.29.192-beta-RC1")
    val yml: String = openApi.toYaml
  }

  def startServer(): Unit = {

    import AkkaRoutes._
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.Http
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer

    val routes: Route = getBooksRoute ~ getBookCoverRoute ~ addBookRoute ~ new SwaggerUI(Documentation.yml).routes
    implicit val actorSystem: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    Await.result(Http().bindAndHandle(routes, "localhost", 8080), 1.minute)
    println("Server started, visit http://localhost:8080/docs for the API docs")
  }

  startServer()
}

class SwaggerUI(yml: String) {

  import akka.http.scaladsl.model.StatusCodes
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route
  import java.util.Properties

  val DocsYml = "docs.yml"

  private val redirectToIndex: Route =
    redirect(s"/docs/index.html?url=/docs/$DocsYml", StatusCodes.PermanentRedirect) //

  private val swaggerUiVersion = {
    val p = new Properties()
    p.load(getClass.getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties"))
    p.getProperty("version")
  }

  val routes: Route =
    path("docs") {
      redirectToIndex
    } ~
      pathPrefix("docs") {
        path("") { // this is for trailing slash
          redirectToIndex
        } ~
          path(DocsYml) {
            complete(yml)
          } ~
          getFromResourceDirectory(s"META-INF/resources/webjars/swagger-ui/$swaggerUiVersion/")
      }
}
