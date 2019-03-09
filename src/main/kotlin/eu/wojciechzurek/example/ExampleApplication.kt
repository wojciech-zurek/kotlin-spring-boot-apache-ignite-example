package eu.wojciechzurek.example

import org.apache.ignite.Ignite
import org.apache.ignite.Ignition
import org.apache.ignite.cache.query.annotations.QuerySqlField
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.configuration.IgniteConfiguration
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.support.beans
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse.*
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import java.net.URI
import java.util.*
import javax.cache.Cache

private val log = LoggerFactory.getLogger(ExampleApplication::class.java)
const val CACHE_NAME = "exampleCache"

@SpringBootApplication
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args) {
        addInitializers(beans)
    }
}

val beans = beans {
    bean("igniteInstance") { ignite() }
    bean { UserRepository.get(ref()) }
    bean<UserHandler>()
    bean { routes(ref()) }
    bean { runner(ref()) }
}

fun ignite(): Ignite {
    val config = IgniteConfiguration()

    val cache = CacheConfiguration<String, User>(CACHE_NAME)
    cache.setIndexedTypes(String::class.java, User::class.java)

    config.setCacheConfiguration(cache)
    return Ignition.start(config)
}

fun runner(userRepository: UserRepository) = CommandLineRunner { userRepository.init() }

fun routes(userHandler: UserHandler) = router {
    "/api".nest {
        GET("/users", userHandler::findAll)
        POST("/users", userHandler::new)
        GET("/users/{id}", userHandler::findById)
        PUT("/users/{id}", userHandler::update)
        DELETE("/users/{id}", userHandler::delete)
    }
}

class UserHandler(private val userRepository: UserRepository) {

    fun findAll(request: ServerRequest) = ok().body(userRepository.findAll(), User::class.java)

    fun findById(request: ServerRequest) = userRepository
            .findById(request.pathVariable("id"))
            .flatMap { ok().syncBody(it) }
            .switchIfEmpty(notFound().build())

    fun new(request: ServerRequest) = request
            .bodyToMono(UserRequest::class.java)
            .map { User(login = it.login, age = it.age) }
            .flatMap { userRepository.save(it) }
            .flatMap { created(URI.create("/api/users/${it.id}")).syncBody(it) }

    fun update(request: ServerRequest) = request
            .bodyToMono(UserRequest::class.java)
            .zipWith(userRepository.findById(request.pathVariable("id")))
            .map { User(it.t2.id, it.t1.login, it.t1.age) }
            .flatMap { userRepository.save(it) }
            .flatMap { ok().syncBody(it) }
            .switchIfEmpty(notFound().build())

    fun delete(request: ServerRequest) = userRepository
            .findById(request.pathVariable("id"))
            .flatMap { userRepository.delete(it).then(noContent().build()) }
            .switchIfEmpty(notFound().build())
}

data class UserRequest(
        val login: String,
        val age: Int
)

data class User(
        @QuerySqlField(index = true)
        val id: String = UUID.randomUUID().toString(),
        val login: String,
        val age: Int
)

abstract class RepositoryProvider<T> {
    var instance: T? = null
    var mock: T? = null
    abstract fun create(ignite: Ignite): T
    fun get(ignite: Ignite): T = mock ?: instance ?: create(ignite)
            .also { instance = it }
}

interface UserRepository {

    companion object : RepositoryProvider<UserRepository>() {
        override fun create(ignite: Ignite) = UserRepositoryImpl(ignite)
    }

    fun init()
    fun findById(id: String): Mono<User>
    fun save(user: User): Mono<User>
    fun delete(user: User): Mono<Unit>
    fun findAll(): Flux<User>
}

class UserRepositoryImpl(ignite: Ignite) : UserRepository {

    private val cache = ignite.cache<String, User>(CACHE_NAME)

    override fun init() {
        cache.clear()
        Flux.just(
                User(id = "e2ac4fba-ce48-42fe-a0b9-c7555b65154f", login = "test", age = 10),
                User(id = "10b86e02-109d-488a-8e25-8bb63a7c4f1c", login = "wojtek", age = 18),
                User(id = "4ca315c0-e214-4b62-9c2a-71d78e29412e", login = "admin", age = 60)
        ).map {
            cache.put(it.id, it)
            it.id
        }.map {
            cache.get(it)
        }.subscribe {
            log.info(it.toString())
        }
    }

    override fun findAll() = Flux.create<User> { sink ->
        cache.asSequence().forEach {
            sink.next(it.value)
        }
        sink.complete()
    }

    override fun findById(id: String) = Mono.create<User> { sink ->
        cache.getAsync(id).listen { future ->
            future.get().let {
                when (it) {
                    null -> sink.success()
                    else -> sink.success(it)
                }
            }
        }
    }

    override fun save(user: User) = Mono.create<User> { sink ->
        cache.putAsync(user.id, user).listen { future ->
            future.get() ?: sink.success(user) ?: sink.error(RuntimeException("Unknown error"))
        }
    }

    override fun delete(user: User) = Mono.create<Unit> { sink ->
        cache.remove(user.id)
        sink.success()
//        cache.removeAsync(user.id).listen { future ->
//            future.get() ?: sink.success() ?: sink.error(RuntimeException("Unknown error"))
//        }
    }
}