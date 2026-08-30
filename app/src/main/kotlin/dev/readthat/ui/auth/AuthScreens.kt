package dev.readthat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.brand.ReadThatLogo
import dev.readthat.shared.AuthForm
import dev.readthat.shared.AuthMode
import dev.readthat.ui.theme.ReadThatOrange

@Composable
fun WelcomeScreen(
    backendEnabled: Boolean,
    onCreateAccount: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        ReadThatLogo(
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
            contentDescription = "ReadThat",
        )
        Spacer(Modifier.height(28.dp))
        Text("Find your people", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text(
            "Fresh conversations, real communities, and the wonderfully specific corners of the internet.",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (!backendEnabled) {
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
            enabled = backendEnabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ReadThatOrange),
        ) { Text("Create account", fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = onLogin,
            enabled = backendEnabled,
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp),
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
fun AuthScreen(
    form: AuthForm,
    onBack: () -> Unit,
    onMode: (AuthMode) -> Unit,
    onUsername: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                onMode(if (form.mode == AuthMode.Register) AuthMode.Login else AuthMode.Register)
            }) {
                Text(if (form.mode == AuthMode.Register) "Log in" else "Sign up", fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (form.mode == AuthMode.Register) "Hi new friend" else "Welcome back",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (form.mode == AuthMode.Register) "Create an account to get started" else "Log in to continue the conversation",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.username,
                onValueChange = onUsername,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                supportingText = form.usernameError?.let { { Text(it) } },
                isError = form.username.isNotEmpty() && form.usernameError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            if (form.mode == AuthMode.Register) {
                OutlinedTextField(
                    value = form.displayName,
                    onValueChange = onDisplayName,
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    supportingText = form.displayNameError?.let { { Text(it) } },
                    isError = form.displayName.isNotEmpty() && form.displayNameError != null,
                )
            }
            OutlinedTextField(
                value = form.password,
                onValueChange = onPassword,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                visualTransformation = if (form.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(if (form.passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password")
                    }
                },
                supportingText = form.passwordError?.let { { Text(it) } },
                isError = form.password.isNotEmpty() && form.passwordError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSubmit,
                enabled = form.canSubmit,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReadThatOrange),
            ) {
                if (form.submitting) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                else Text(if (form.mode == AuthMode.Register) "Create account" else "Continue", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}
