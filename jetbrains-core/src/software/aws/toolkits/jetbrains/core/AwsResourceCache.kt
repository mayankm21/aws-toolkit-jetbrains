package software.aws.toolkits.jetbrains.core

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import software.amazon.awssdk.services.lambda.LambdaClient
import software.aws.toolkits.jetbrains.services.lambda.LambdaFunction
import software.aws.toolkits.jetbrains.services.lambda.toDataClass
import java.util.concurrent.ConcurrentHashMap

//TODO to be replaced with an actual resource implementation

interface AwsResourceCache {
    fun lambdaFunctions(): List<LambdaFunction>

    companion object {
        fun getInstance(project: Project): AwsResourceCache = ServiceManager.getService(project, AwsResourceCache::class.java)
    }
}

class DefaultAwsResourceCache(private val settings: AwsSettingsProvider, private val clientManager: AwsClientManager) : AwsResourceCache {
    private val cache = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun lambdaFunctions(): List<LambdaFunction> =
            cache.computeIfAbsent("${settings.currentRegion.id}:${settings.currentProfile?.name}:lambdafunctions", {
                val client = clientManager.getClient<LambdaClient>()
                client.listFunctionsIterable().functions().map { it.toDataClass(client) }.toList()
            }) as List<LambdaFunction>
}