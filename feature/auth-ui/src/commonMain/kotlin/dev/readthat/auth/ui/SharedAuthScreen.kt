package dev.readthat.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.readthat.client.SharedAuthState
import dev.readthat.core.ui.brand.ReadThatLogo
import dev.readthat.core.ui.theme.ReadThatOrange
import dev.readthat.shared.AuthForm
import dev.readthat.shared.AuthMode

/** Canonical signed-out destination. The host applies safe-area and IME insets exactly once. */
@Composable
fun SharedAuthScreen(
    state: SharedAuthState,
    onMode: (AuthMode) -> Unit,
    onUsername: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showForm by rememberSaveable { mutableStateOf(shouldOpenAuthForm(state.form)) }
    if (!showForm) {
        WelcomeContent(
            state = state,
            onCreateAccount = { onMode(AuthMode.Register); showForm = true },
            onLogin = { onMode(AuthMode.Login); showForm = true },
            onClearMessage = onClearMessage,
            modifier = modifier,
        )
    } else {
        AuthFormContent(
            form = state.form,
            backendEnabled = state.backendEnabled,
            message = state.message,
            onBack = { showForm = false },
            onMode = onMode,
            onUsername = onUsername,
            onDisplayName = onDisplayName,
            onPassword = onPassword,
            onTogglePassword = onTogglePassword,
            onSubmit = onSubmit,
            onClearMessage = onClearMessage,
            modifier = modifier,
        )
    }
}

@Composable
private fun WelcomeContent(
    state: SharedAuthState,
    onCreateAccount: () -> Unit,
    onLogin: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        ReadThatLogo(
            Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
            contentDescription = "ReadThat",
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Find your people",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            "Fresh conversations, real communities, and the wonderfully specific corners of the internet.",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        state.message?.let { AuthMessage(it, onClearMessage) }
        if (!state.backendEnabled) {
            Text(
                "This build has no Cloudflare API URL. Rebuild with READTHAT_API_BASE_URL to create or sign in to an account.",
                Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onCreateAccount,
            enabled = state.backendEnabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ReadThatOrange),
        ) { Text("Create account", fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = onLogin,
            enabled = state.backendEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(54.dp),
            shape = RoundedCornerShape(28.dp),
        ) { Text("Log in", fontWeight = FontWeight.Bold) }
        Text(
            "By continuing, you agree to this sample app's test environment and privacy policy.",
            Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AuthFormContent(
    form: AuthForm,
    backendEnabled: Boolean,
    message: String?,
    onBack: () -> Unit,
    onMode: (AuthMode) -> Unit,
    onUsername: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !form.submitting) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    onMode(if (form.mode == AuthMode.Register) AuthMode.Login else AuthMode.Register)
                },
                enabled = !form.submitting,
            ) {
                Text(if (form.mode == AuthMode.Register) "Log in" else "Sign up", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            if (form.mode == AuthMode.Register) "Hi new friend" else "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Text(
            if (form.mode == AuthMode.Register) "Create an account to get started"
            else "Log in to continue the conversation",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        message?.let { AuthMessage(it, onClearMessage) }
        OutlinedTextField(
            form.username,
            onUsername,
            Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true,
            enabled = !form.submitting,
            isError = form.username.isNotEmpty() && form.usernameError != null,
            supportingText = form.usernameError?.takeIf { form.username.isNotEmpty() }?.let { error ->
                { Text(error) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = RoundedCornerShape(20.dp),
        )
        if (form.mode == AuthMode.Register) {
            OutlinedTextField(
                form.displayName,
                onDisplayName,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Display name") },
                singleLine = true,
                enabled = !form.submitting,
                isError = form.displayName.isNotEmpty() && form.displayNameError != null,
                supportingText = form.displayNameError?.takeIf { form.displayName.isNotEmpty() }?.let { error ->
                    { Text(error) }
                },
                shape = RoundedCornerShape(20.dp),
            )
        }
        OutlinedTextField(
            form.password,
            onPassword,
            Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Password") },
            visualTransformation = if (form.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            singleLine = true,
            enabled = !form.submitting,
            isError = form.password.isNotEmpty() && form.passwordError != null,
            supportingText = form.passwordError?.takeIf { form.password.isNotEmpty() }?.let { error ->
                { Text(error) }
            },
            trailingIcon = {
                TextButton(onClick = onTogglePassword, enabled = !form.submitting) {
                    Text(if (form.passwordVisible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (form.canSubmit && backendEnabled) onSubmit() }),
            shape = RoundedCornerShape(20.dp),
        )
        form.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = form.canSubmit && backendEnabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ReadThatOrange),
        ) {
            if (form.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (form.mode == AuthMode.Register) "Create account" else "Continue", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AuthMessage(message: String, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onClear) { Text("Dismiss") }
        }
    }
}

internal fun shouldOpenAuthForm(form: AuthForm): Boolean =
    form.username.isNotBlank() || form.displayName.isNotBlank() || form.password.isNotBlank() ||
        form.submitting || form.error != null
