package com.nokta.pos.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

@Serializable
private data class ApiErrorBody(
    val message: String? = null,
    val error: String? = null,
    val statusCode: Int? = null,
)

private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Mensagem exata devolvida por `POST venue-orders/:id/send` (backend,
 * `VenueOrdersService.send`) quando o pedido já saiu de DRAFT. Diferente de
 * `createOrder` (idempotente por `clientRequestId`), `sendOrder` não é
 * idempotente — reenviar um pedido já enviado sempre recusa com 400. Quem
 * chama `createOrder`+`sendOrder` em sequência (SyncEngine, TabRepository)
 * precisa tratar esse 400 específico como sucesso (o pedido já está
 * enviado de verdade), nunca como falha visível ao operador.
 */
const val ORDER_ALREADY_SENT_MESSAGE = "Este pedido já foi enviado."

/**
 * `HttpException.message()` do Retrofit é só "HTTP 400"/"Bad Request" — nunca
 * o motivo real. O backend (NestJS) sempre devolve o motivo de verdade no
 * corpo (`{"message": "..."}`, às vezes uma lista de erros de validação em
 * vez de string). Sem isto, qualquer 400/404/409 chega ao operador como
 * "HTTP 400" cru, o que não diz o que fazer.
 */
fun Throwable.humanizedApiMessage(fallback: String = "Não foi possível concluir a operação."): String {
    val http = this as? HttpException ?: return message ?: fallback
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    val parsedMessage = raw?.let { body ->
        runCatching { errorJson.decodeFromString(ApiErrorBody.serializer(), body) }.getOrNull()?.message
            ?: runCatching { errorJson.parseToJsonElement(body) }.getOrNull()
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("message")
                ?.let { field ->
                    when (field) {
                        is JsonArray -> field.joinToString("; ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                        is JsonPrimitive -> field.content
                        else -> null
                    }
                }
    }
    return parsedMessage?.takeIf { it.isNotBlank() } ?: "$fallback (HTTP ${http.code()})"
}
