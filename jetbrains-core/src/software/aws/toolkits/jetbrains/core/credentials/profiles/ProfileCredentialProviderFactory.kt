// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials.profiles

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Ref
import icons.AwsIcons
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.ProcessCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.profiles.Profile
import software.amazon.awssdk.profiles.ProfileProperty
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.aws.toolkits.core.ToolkitClientManager
import software.aws.toolkits.core.credentials.ToolkitCredentialsIdentifier
import software.aws.toolkits.core.credentials.ToolkitCredentialsProvider
import software.aws.toolkits.core.region.AwsRegion
import software.aws.toolkits.jetbrains.core.AwsClientManager
import software.aws.toolkits.jetbrains.core.credentials.CorrectThreadCredentialsProvider
import software.aws.toolkits.jetbrains.core.credentials.CredentialIdentifierChange
import software.aws.toolkits.jetbrains.core.credentials.CredentialProviderFactory
import software.aws.toolkits.jetbrains.core.credentials.CredentialsChangeListener
import software.aws.toolkits.jetbrains.utils.tryNotify
import software.aws.toolkits.resources.message
import java.util.function.Supplier

const val DEFAULT_PROFILE_ID = "profile:default"

private class ProfileCredentialsIdentifier(internal val profileName: String) : ToolkitCredentialsIdentifier() {
    override val id = "profile:$profileName"
    override val displayName get() = message("credentials.profile.name", profileName)
}

class ProfileCredentialProviderFactory : CredentialProviderFactory, Disposable {
    private val profileWatcher = ProfileWatcher(this)
    private val profileHolder = ProfileHolder()

    override fun setUp(credentialLoadCallback: CredentialsChangeListener) {
        // Load the initial data, then start the background watcher
        loadProfiles(credentialLoadCallback)

        profileWatcher.start {
            loadProfiles(credentialLoadCallback)
        }
    }

    private fun loadProfiles(credentialLoadCallback: CredentialsChangeListener) {
        val profilesAdded = mutableListOf<ProfileCredentialsIdentifier>()
        val profilesModified = mutableListOf<ProfileCredentialsIdentifier>()
        val profilesRemoved = mutableListOf<ProfileCredentialsIdentifier>()

        val previousProfilesSnapshot = profileHolder.snapshot()
        val newProfiles = tryNotify("Failed to load credential file(s)") {
            validateAndGetProfiles()
        } ?: return

        newProfiles.validProfiles.forEach {
            val previousProfile = previousProfilesSnapshot.remove(it.key)
            if (previousProfile == null) {
                // It was not in the snapshot, so it must be new
                profilesAdded.add(ProfileCredentialsIdentifier(it.key))
            } else {
                // If the profile was modified, notify people, else do nothing
                if (previousProfile != it.value) {
                    profilesModified.add(ProfileCredentialsIdentifier(it.key))
                }
            }
        }

        // Any remaining profiles must have either become invalid or removed from the cred/config files
        previousProfilesSnapshot.keys.asSequence().map { ProfileCredentialsIdentifier(it) }.toCollection(profilesRemoved)

        profileHolder.update(newProfiles.validProfiles)
        credentialLoadCallback(CredentialIdentifierChange(profilesAdded, profilesModified, profilesRemoved))

        // TODO: Notify invalid profiles
    }

    override fun dispose() {}

    override fun createAwsCredentialProvider(
        providerId: ToolkitCredentialsIdentifier,
        region: AwsRegion,
        sdkClient: SdkHttpClient
    ): ToolkitCredentialsProvider {
        val profileProviderId = providerId as? ProfileCredentialsIdentifier
            ?: throw IllegalStateException("ProfileCredentialProviderFactory can only handle ProfileCredentialsIdentifier, but got ${providerId::class}")

        val profile = profileHolder.getProfile(profileProviderId.profileName)
            ?: throw IllegalStateException("Profile ${profileProviderId.profileName} looks to have been removed")

        return ToolkitCredentialsProvider(
            profileProviderId,
            createAwsCredentialProvider(profile, region, sdkClient)
        )
    }

    private fun createAwsCredentialProvider(
        profile: Profile,
        region: AwsRegion,
        sdkClient: SdkHttpClient
    ) = when {
        profile.propertyExists(ProfileProperty.ROLE_ARN) -> createAssumeRoleProvider(profile, region, sdkClient)
        profile.propertyExists(ProfileProperty.AWS_SESSION_TOKEN) -> createStaticSessionProvider(profile)
        profile.propertyExists(ProfileProperty.AWS_ACCESS_KEY_ID) -> createBasicProvider(profile)
        profile.propertyExists(ProfileProperty.CREDENTIAL_PROCESS) -> createCredentialProcessProvider(profile)
        else -> {
            throw IllegalArgumentException(message("credentials.profile.unsupported", profile.name()))
        }
    }

    private fun createAssumeRoleProvider(
        profile: Profile,
        region: AwsRegion,
        sdkClient: SdkHttpClient
    ): AwsCredentialsProvider {
        val sourceProfileName = profile.requiredProperty(ProfileProperty.SOURCE_PROFILE)
        val sourceProfile = profileHolder.getProfile(sourceProfileName)
            ?: throw IllegalStateException("Profile $sourceProfileName looks to have been removed")

        // Override the default SPI for getting the active credentials since we are making an internal
        // to this provider client
        val stsClient = ToolkitClientManager.createNewClient(
            StsClient::class,
            sdkClient,
            Region.of(region.id),
            createAwsCredentialProvider(sourceProfile, region, sdkClient),
            AwsClientManager.userAgent
        )

        val roleArn = profile.requiredProperty(ProfileProperty.ROLE_ARN)
        val roleSessionName = profile.property(ProfileProperty.ROLE_SESSION_NAME)
            .orElseGet { "aws-toolkit-jetbrains-${System.currentTimeMillis()}" }
        val externalId = profile.property(ProfileProperty.EXTERNAL_ID)
            .orElse(null)
        val mfaSerial = profile.property(ProfileProperty.MFA_SERIAL)
            .orElse(null)

        return CorrectThreadCredentialsProvider(
            StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(Supplier {
                    createAssumeRoleRequest(
                        profile.name(),
                        mfaSerial,
                        roleArn,
                        roleSessionName,
                        externalId
                    )
                })
                .build()
        )
    }

    private fun createAssumeRoleRequest(
        profileName: String,
        mfaSerial: String?,
        roleArn: String,
        roleSessionName: String?,
        externalId: String?
    ): AssumeRoleRequest = AssumeRoleRequest.builder()
        .roleArn(roleArn)
        .roleSessionName(roleSessionName)
        .externalId(externalId).also { request ->
            mfaSerial?.let { _ ->
                request.serialNumber(mfaSerial)
                    .tokenCode(promptMfaToken(profileName, mfaSerial))
            }
        }.build()

    private fun promptMfaToken(name: String, mfaSerial: String): String {
        val result = Ref<String>()

        ApplicationManager.getApplication().invokeAndWait({
            val mfaCode: String = Messages.showInputDialog(
                message("credentials.profile.mfa.message", mfaSerial),
                message("credentials.profile.mfa.title", name),
                AwsIcons.Logos.IAM_LARGE
            ) ?: throw IllegalStateException("MFA challenge is required")

            result.set(mfaCode)
        }, ModalityState.any())

        return result.get()
    }

    private fun createBasicProvider(profile: Profile) = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(
            profile.requiredProperty(ProfileProperty.AWS_ACCESS_KEY_ID),
            profile.requiredProperty(ProfileProperty.AWS_SECRET_ACCESS_KEY)
        )
    )

    private fun createStaticSessionProvider(profile: Profile) = StaticCredentialsProvider.create(
        AwsSessionCredentials.create(
            profile.requiredProperty(ProfileProperty.AWS_ACCESS_KEY_ID),
            profile.requiredProperty(ProfileProperty.AWS_SECRET_ACCESS_KEY),
            profile.requiredProperty(ProfileProperty.AWS_SESSION_TOKEN)
        )
    )

    private fun createCredentialProcessProvider(profile: Profile) = ProcessCredentialsProvider.builder()
        .command(profile.requiredProperty(ProfileProperty.CREDENTIAL_PROCESS))
        .build()
}

private class ProfileHolder {
    private val profiles = mutableMapOf<String, Profile>()

    fun snapshot() = profiles.toMutableMap()

    fun update(validProfiles: Map<ProfileName, Profile>) {
        profiles.clear()
        profiles.putAll(validProfiles)
    }

    fun getProfile(profileName: String): Profile? = profiles[profileName]
}
