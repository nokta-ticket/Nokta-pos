// Import explícito: dentro do bloco `android { }` o identificador `java`
// resolve para a extensão do AGP, não para o pacote java.* — sem isto,
// `java.util.Properties` abaixo não compila.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nokta.pos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nokta.pos"
        minSdk = 29 // Cielo Smart exige Android 10+ (seção 5 do manual de certificação)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * Assinatura do release — exigida para publicar/homologar (um APK
     * não assinado, ou assinado com a chave de debug, é recusado).
     *
     * A keystore NUNCA vai versionada: os valores vêm de
     * `keystore.properties` na raiz do projeto (fora do git, ver
     * .gitignore) ou de variáveis de ambiente no CI. Sem esses valores, a
     * config simplesmente não é criada e o build de release falha de forma
     * explícita em vez de gerar um APK inutilizável em silêncio.
     */
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        // reader(UTF-8), NUNCA inputStream(): Properties.load(InputStream) lê
        // como ISO-8859-1 por especificação, e o caminho da keystore deste
        // projeto passa por "C:\Users\Usuário\..." — o "á" virava "Ã¡" e o
        // Gradle falhava com "Keystore file not found" apontando um caminho
        // corrompido, sem nenhuma pista de que a causa era encoding.
        if (keystorePropsFile.exists()) keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
    val releaseStorePath = keystoreProps.getProperty("storeFile") ?: System.getenv("NOKTA_KEYSTORE_FILE")
    val releaseStorePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("NOKTA_KEYSTORE_PASSWORD")
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("NOKTA_KEY_ALIAS")
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("NOKTA_KEY_PASSWORD")
    val hasReleaseSigning = releaseStorePath != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 (JAR signing) fica DESLIGADA de propósito: só é
                // necessária abaixo do Android 7, e o minSdk aqui é 29
                // (exigência da Cielo Smart). O AGP a ignora quando
                // minSdk >= 24 mesmo se pedida — deixá-la `true` dava a
                // falsa impressão de que o APK era v1-assinado, quando o
                // `apksigner verify` mostra "v1: false".
                //
                // Só v2, deliberadamente. Ligar v3 junto FEZ o AGP publicar
                // apenas o bloco v3 (verificado com `apksigner verify`:
                // "v2: false, v3: true"), o que reduziria a compatibilidade
                // para Android 9+ sem nenhum ganho aqui — a rotação de
                // chave que a v3 habilita não está em uso. v2 é o esquema
                // que todo terminal com Android 10+ valida.
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Seção 53 do PRD: development / staging / production, nunca credenciais
    // misturadas. Cada flavor define API_BASE_URL e o ambiente Cielo (o
    // Client-ID/Access Token da Cielo NUNCA vai hardcoded aqui — são
    // inseridos no momento do pareamento do terminal, vindos do backend
    // Nokta, nunca embutidos no APK. Ver docs/cielo-smart-integration.md).
    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3333/api/\"")
            buildConfigField("String", "ENVIRONMENT", "\"development\"")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            buildConfigField("String", "API_BASE_URL", "\"https://staging-api.nokta.live/api/\"")
            buildConfigField("String", "ENVIRONMENT", "\"staging\"")
        }
        create("production") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://api.nokta.live/api/\"")
            buildConfigField("String", "ENVIRONMENT", "\"production\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // ProcessLifecycleOwner — sabe quando o app inteiro está em primeiro
    // plano (ON_START/ON_STOP), independente de qual Activity/tela está
    // aberta. Usado pelo DeviceHeartbeatCoordinator.
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // DI
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Rede
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Persistência local — sessão/credenciais/pareamento (chave-valor simples,
    // continua em DataStore) e o banco operacional (Room), fonte de verdade
    // da UI para cardápio/mesas/comandas/fila de sincronização offline-first.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Sincronização em background — sobrevive a fechamento do app e a reboot,
    // respeita conectividade nativamente (NetworkType.CONNECTED) e já traz
    // backoff/retry sem reinventar um scheduler próprio.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Imagens do cardápio. Cache em disco próprio do Coil: o garçom reconhece
    // o produto pela foto muito mais rápido que pelo nome, e sem cache a lista
    // ficaria inútil no salão sem rede.
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    // Room real (não fake) rodando na JVM via Robolectric — valida o schema,
    // as constraints (@@unique de idempotência) e as queries de verdade, sem
    // precisar de emulador/dispositivo físico para a suíte rodar no CI.
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
