package eu.wojciechzurek.example

import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.test.StepVerifier

@RunWith(SpringRunner::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [ExampleApplication::class])
class ExampleApplicationTests {

    //private val client = WebTestClient.bindToServer().baseUrl("http://localhost:8080").build()
    @Autowired
    private lateinit var client: WebTestClient

    @Test
    fun contextLoads() {
    }


    @Test
    fun `Get all users endpoint`() {
        client
                .get()
                .uri("/api/users")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectHeader().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
                .expectBodyList(User::class.java)
    }

    @Test
    fun `Get one user endpoint`() {
        val result = client
                .get()
                .uri("/api/users/e2ac4fba-ce48-42fe-a0b9-c7555b65154f")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectHeader().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
                .returnResult(User::class.java)

        StepVerifier
                .create(result.responseBody)
                .expectNextMatches { it.login == "test" && it.age == 10 }
                .thenCancel()
                .verify()
    }

    @Test
    fun `Update one user endpoint`() {

        val userRequest = UserRequest(login = "update-login", age = 40)

        val result = client
                .put()
                .uri("/api/users/10b86e02-109d-488a-8e25-8bb63a7c4f1c")
                .syncBody(userRequest)
                .exchange()
                .expectStatus().is2xxSuccessful
                .returnResult(User::class.java)

        StepVerifier
                .create(result.responseBody)
                .expectNextMatches { it.login == userRequest.login && it.age == userRequest.age }
                .thenCancel()
                .verify()
    }

    @Test
    fun `Post new user endpoint`() {

        val user = UserRequest(login = "super-test", age = 99)

        val result = client
                .post()
                .uri("/api/users")
                .syncBody(user)
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectHeader().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
                .expectHeader().exists(HttpHeaders.LOCATION)
                .returnResult(User::class.java)

        StepVerifier
                .create(result.responseBody)
                .expectNextMatches { it.login == user.login && it.age == user.age }
                .thenCancel()
                .verify()
    }

    @Test
    fun `Deleter one user endpoint`() {
        client
                .delete()
                .uri("/api/users/4ca315c0-e214-4b62-9c2a-71d78e29412e")
                .exchange()
                .expectStatus().isNoContent
                .expectBody().isEmpty

    }

}
