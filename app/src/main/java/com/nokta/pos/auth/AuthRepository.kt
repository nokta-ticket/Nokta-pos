package com.nokta.pos.auth

import android.os.SystemClock
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.device.DeviceCredentialsStore
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.DeviceLoginRequest
import com.nokta.pos.network.dto.RedeemPairingCodeRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instante (epoch millis) em que o kernel atual deu boot — constante durante
 * toda a vida do processo/kernel, e só muda quando o aparelho reinicia de
 * verdade. `elapsedRealtime()` é "tempo desde o boot", então subtraí-lo do
 * relógio de parede dá sempre o mesmo valor entre fechar/reabrir o app
 * (mesmo boot) e um valor diferente depois de um reboot real.
 */
private fun currentBootInstant(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

/** Ver [AuthRepository.isSessionExpired]. */
private const val BOOT_INSTANT_TOLERANCE_MS = 60_000L

/**
 * `sessionExpiresAt` chega como ISO-8601 do backend. Convertido para epoch
 * millis sem depender de java.time (minSdk 29 tem, mas evita desugaring
 * desnecessário) — falha de parse vira `null`, tratado como "sem expiração
 * conhecida", nunca como sessão expirada (não derruba o operador por um
 * formato inesperado).
 */
private fun parseIsoToEpochMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
}

sealed class LoginOutcome {
    data class Success(val userName: String, val role: String) : LoginOutcome()
    data object Requires2fa : LoginOutcome()
    data class Failed(val message: String) : LoginOutcome()
}

/**
 * Dois estágios de identidade, nunca confundidos (ver
 * docs/pos-mvp-architecture.md): o TERMINAL prova quem é via pareamento
 * (uma vez, por gerente); o OPERADOR prova quem é via login (toda troca de
 * turno). Perder o pareamento exige o produtor gerar um novo código; perder
 * a sessão do operador só exige logar de novo — os dois nunca se substituem.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: NoktaApi,
    private val credentialsStore: DeviceCredentialsStore,
) {

    fun isDevicePaired(): Boolean = credentialsStore.isPaired()
    fun isOperatorLoggedIn(): Boolean = credentialsStore.jwt() != null
    fun currentOperatorRole(): String? = credentialsStore.currentUserRole()
    fun currentOperatorName(): String? = credentialsStore.currentUserName()
    fun currentOrganizationId(): Long? = credentialsStore.organizationId()
    fun currentLocationId(): Long? = credentialsStore.locationId()

    suspend fun redeemPairingCode(code: String): Result<Unit> = runCatching {
        val response = api.redeemPairingCode(RedeemPairingCodeRequest(code))
        credentialsStore.saveDeviceToken(response.deviceToken)
    }

    suspend fun login(email: String, senha: String): LoginOutcome = runCatching {
        val response = api.deviceLogin(DeviceLoginRequest(email, senha))
        if (response.requires2fa) return LoginOutcome.Requires2fa

        val token = response.token ?: return LoginOutcome.Failed("Resposta de login inválida.")
        val user = response.user ?: return LoginOutcome.Failed("Resposta de login inválida.")
        val organizationId = response.organizationId ?: return LoginOutcome.Failed("Terminal sem unidade configurada.")
        val locationId = response.location?.id ?: return LoginOutcome.Failed("Terminal sem unidade configurada.")

        credentialsStore.saveSession(
            jwt = token,
            userId = user.userId,
            userName = user.displayName,
            role = user.role,
            organizationId = organizationId,
            locationId = locationId,
            locationName = response.location.nome,
            sessionExpiresAtEpochMs = parseIsoToEpochMillis(response.sessionExpiresAt),
        )
        credentialsStore.saveSessionBootInstant(currentBootInstant())

        // Cardápio principal resolvido pelo backend — nunca mais um menuId fixo no cliente.
        response.mainMenu?.let { credentialsStore.saveMainMenuId(it.id) }

        response.posConfig?.let {
            credentialsStore.savePosConfig(
                operationMode = it.operationMode,
                requireOpenCashSessionForPayments = it.requireOpenCashSessionForPayments,
                blockTabCloseWithPendingItems = it.blockTabCloseWithPendingItems,
            )
        }

        response.cielo?.let { credentialsStore.saveCieloCredentials(it.clientId, it.accessToken) }
            ?: credentialsStore.clearCieloCredentials()

        // Permissões do operador: falha aqui NUNCA impede o login. Sem elas o
        // app opera no modo permissivo e o backend continua barrando o que o
        // operador não pode fazer (ver OperatorAccess.PERMISSIVE).
        refreshAccess(organizationId)

        LoginOutcome.Success(userName = user.displayName, role = user.role)
    }.getOrElse { LoginOutcome.Failed(humanizeLoginError(it)) }

    /**
     * `HttpException.message` do Retrofit é algo como "HTTP 403 Forbidden" —
     * não diz nada útil ao operador. 401/403 no login sempre significa
     * credencial errada (device-login não distingue "e-mail não existe" de
     * "senha errada", por segurança); qualquer outro código HTTP ou falha de
     * rede usa a mensagem genérica.
     */
    private fun humanizeLoginError(e: Throwable): String {
        val code = (e as? retrofit2.HttpException)?.code()
        return when (code) {
            401, 403 -> "E-mail ou senha incorretos."
            null -> "Não foi possível entrar. Verifique a conexão."
            else -> "Não foi possível entrar (erro $code). Tente novamente."
        }
    }

    /** Relê as permissões do operador. Silencioso por design — ver comentário em `login`. */
    suspend fun refreshAccess(organizationId: Long) {
        runCatching { api.getMeAccess(organizationId) }
            .onSuccess { access ->
                access.venue?.let { credentialsStore.savePermissions(it.permissions.toSet()) }
            }
    }

    /**
     * O que o operador pode fazer. Sem nenhuma permissão salva (offline no
     * primeiro login, ou organização sem módulo Venue resolvido) devolve o
     * conjunto permissivo — o backend é quem barra de verdade.
     */
    fun currentAccess(): OperatorAccess {
        val permissions = credentialsStore.permissions()
        if (permissions.isEmpty()) return OperatorAccess.PERMISSIVE
        return OperatorAccess(role = credentialsStore.currentUserRole(), permissions = permissions)
    }

    fun mainMenuId(): Long? = credentialsStore.mainMenuId()
    fun locationName(): String? = credentialsStore.locationName()
    fun operationMode(): String? = credentialsStore.operationMode()
    fun requiresOpenCashSessionForPayments(): Boolean = credentialsStore.requiresOpenCashSessionForPayments()

    /**
     * Sessão vencida de verdade segundo o relógio local — o JWT não tem mais
     * nenhuma garantia de validade, com ou sem rede. Checagem barata antes de
     * bater na rede; o backend continua sendo quem barra de fato via 401.
     */
    fun isSessionExpired(): Boolean {
        val expiresAt = credentialsStore.sessionExpiresAt() ?: return false
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * O aparelho reiniciou desde o último login/confirmação — a sessão em si
     * ainda pode ser válida (JWT não vencido), mas não temos mais certeza de
     * QUEM está com a maquininha na mão.
     *
     * Reiniciar a máquina costuma ser exatamente o momento em que ela troca
     * de dono (fim de turno, entrega pro próximo operador). Fechar/reabrir o
     * app sem reiniciar o aparelho é o mesmo boot e nunca cai aqui.
     *
     * Isto é deliberadamente separado de [isSessionExpired]: com rede
     * disponível, reboot sempre exige reconfirmar quem é o operador (mesmo
     * a sessão sendo válida); sem rede, [SplashViewModel] decide reaproveitar
     * a sessão em vez de travar o caixa por falta de internet no exato
     * momento do boot.
     */
    fun didRebootSinceLastSession(): Boolean {
        val sessionBootInstant = credentialsStore.sessionBootInstant() ?: return false
        // Comparação com folga: o relógio de parede pode ajustar por NTP
        // entre boots, então um recálculo exato de `currentBootInstant()`
        // pode divergir por alguns segundos mesmo sem reboot. Só trata como
        // reboot real uma diferença grande o suficiente para não ser isso.
        return kotlin.math.abs(currentBootInstant() - sessionBootInstant) > BOOT_INSTANT_TOLERANCE_MS
    }

    /** Reconfirma a sessão atual após um reboot sem exigir senha de novo — grava o boot instant novo. */
    fun confirmSessionAfterReboot() {
        credentialsStore.saveSessionBootInstant(currentBootInstant())
    }

    /** Troca rápida de operador (seção 8/33 do PRD) — nunca desfaz o pareamento do terminal. */
    fun logoutOperator() = credentialsStore.clearSession()
}
