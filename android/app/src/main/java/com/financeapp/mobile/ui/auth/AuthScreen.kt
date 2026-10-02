package com.financeapp.mobile.ui.auth

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import com.financeapp.mobile.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.financeapp.mobile.util.formatCpfInput
import com.financeapp.mobile.util.isValidCpf
import com.financeapp.mobile.util.normalizeCpf
import com.financeapp.mobile.ui.legal.FinanceAppLegal
import com.financeapp.mobile.ui.legal.LegalAcceptanceStore
import com.financeapp.mobile.ui.legal.LegalConsentDialog
import com.financeapp.mobile.ui.legal.LegalPrivacyDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private data class SexOption(
    val value: String,
    val label: String
)

private val sexOptions = listOf(
    SexOption("male", "Masculino"),
    SexOption("female", "Feminino"),
    SexOption("other", "Outro"),
    SexOption("prefer_not_to_say", "Prefiro não informar")
)

@Composable
private fun GoogleGLogo(modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.18f
        val radius = (size.minDimension - stroke) / 2f
        val topLeft = androidx.compose.ui.geometry.Offset(
            (size.width - radius * 2f) / 2f,
            (size.height - radius * 2f) / 2f
        )
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
        fun arc(color: Color, start: Float, sweep: Float) {
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
        }
        arc(Color(0xFF4285F4), -42f, 86f)
        arc(Color(0xFF34A853), 44f, 84f)
        arc(Color(0xFFFBBC05), 128f, 80f)
        arc(Color(0xFFEA4335), 208f, 110f)
        drawLine(
            color = Color(0xFF4285F4),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.53f, size.height * 0.52f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.91f, size.height * 0.52f),
            strokeWidth = stroke
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.52f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.73f),
            strokeWidth = stroke
        )
    }
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    initialRegisterMode: Boolean = false,
    onLogin: (String, String) -> Unit,
    onForgotPassword: () -> Unit,
    onGuest: () -> Unit,
    onRegister:
        (
            String,
            String,
            String,
            String,
            String,
            String
        ) -> Unit,
    onGoogleSignIn: () -> Unit,
    onBiometricSuccess: () -> Unit,
    onResendRegistrationLink: () -> Unit,
    onCloseEmailVerificationNotice: () -> Unit
) {
    var showGuestNotice by remember { mutableStateOf(false) }
    if (showGuestNotice) {
        GuestNoticeDialog(onDismiss={showGuestNotice=false}, onAccept={showGuestNotice=false;onGuest()})
    }
    var registerMode by remember(initialRegisterMode) { mutableStateOf(initialRegisterMode) }
    var name by remember {
        mutableStateOf("")
    }
    var cpfField by remember {
        mutableStateOf(
            TextFieldValue("")
        )
    }
    val cpf = cpfField.text

    var email by remember {
        mutableStateOf("")
    }

    var birthDateField by remember {
        mutableStateOf(
            TextFieldValue("")
        )
    }
    val birthDate =
        birthDateField.text
    var sex by remember { mutableStateOf("") }
    var sexMenuExpanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var legalAccepted by remember { mutableStateOf(false) }
    var showLegalDocuments by remember { mutableStateOf(false) }
    var pendingLegalAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val changeMode: () -> Unit = {
        registerMode = !registerMode
        localError = null
        password = ""
        confirmPassword = ""
        passwordVisible = false
        confirmPasswordVisible = false
        cpfField = TextFieldValue("")
    }
    BackHandler(enabled = registerMode && !state.loading) { changeMode() }

    val context = LocalContext.current
    if (showLegalDocuments) LegalPrivacyDialog { showLegalDocuments = false }
    pendingLegalAction?.let { action ->
        LegalConsentDialog(
            onAccept = { LegalAcceptanceStore.record(context); pendingLegalAction = null; action() },
            onDismiss = { pendingLegalAction = null }
        )
    }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val selectedSexLabel = sexOptions.firstOrNull {
        it.value == sex
    }?.label ?: "Selecione"

    if (
        state.awaitingEmailVerification
    ) {
        EmailVerificationLinkDialog(
            email =
                state.verificationEmail
                    .orEmpty(),
            loading = state.loading,
            error = state.error,
            onResend =
                onResendRegistrationLink,
            onDismiss = {
                // Cadastro concluído: limpa todo o formulário e volta ao login.
                name = ""
                cpfField = TextFieldValue("")
                email = ""
                birthDateField = TextFieldValue("")
                sex = ""
                password = ""
                confirmPassword = ""
                passwordVisible = false
                confirmPasswordVisible = false
                localError = null
                registerMode = false
                onCloseEmailVerificationNotice()
            }
        )
    }

    val loginBlue = Color(0xFF1478FF)
        val loginPurple = Color(0xFF6D4CFF)
    val loginNavy = Color(0xFF061B31)
    val loginSheet = Color(0xFFF8FBFF)
    val loginText = Color(0xFF10233D)
    val loginSecondary = Color(0xFF6A778C)
    val loginOutline = Color(0xFFD7E0EC)

    val largeScreen = LocalConfiguration.current.screenWidthDp >= 720
    if (largeScreen && !registerMode) {
        LargeScreenLogin(
            state = state,
            email = email,
            password = password,
            passwordVisible = passwordVisible,
            biometricAvailable = biometricAvailable,
            onEmailChange = { email = it; localError = null },
            onPasswordChange = { password = it; localError = null },
            onTogglePassword = { passwordVisible = !passwordVisible },
            onLogin = { onLogin(email.trim(), password) },
            onForgotPassword = onForgotPassword,
            onGoogle = {
                if (LegalAcceptanceStore.acceptedCurrent(context)) onGoogleSignIn()
                else pendingLegalAction = onGoogleSignIn
            },
            onGuest = {
                val action = { showGuestNotice = true }
                if (LegalAcceptanceStore.acceptedCurrent(context)) action()
                else pendingLegalAction = action
            },
            onCreateAccount = changeMode,
            onBiometric = { showBiometricPrompt(context = context, onSuccess = onBiometricSuccess) }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(
                Brush.linearGradient(
                    colors = listOf(loginNavy, Color(0xFF0C3569), loginPurple)
                )
            )
    ) {
        // Elementos decorativos discretos para manter a identidade azul/roxa do app.
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = 210.dp, y = 110.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .offset(x = (-120).dp, y = 240.dp)
                .clip(CircleShape)
                .background(loginBlue.copy(alpha = 0.18f))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = if (registerMode) 34.dp else 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (registerMode) 8.dp else 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.size(if (registerMode) 72.dp else 92.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.financeapp_mark),
                    contentDescription = "FinanceApp",
                    modifier = Modifier.padding(if (registerMode) 8.dp else 10.dp)
                )
            }
            Text(
                "FinanceApp",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = if (registerMode) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
            )
            if (!registerMode) {
                Text(
                    "Sua vida financeira,\nmais simples.",
                    color = Color.White.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (registerMode) 0.79f else 0.65f)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
            colors = CardDefaults.cardColors(containerColor = loginSheet),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                if (registerMode) {
                    Text(
                        "Crie sua conta",
                        color = loginText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            localError = null
                        },
                        label = { Text("Nome completo") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = cpfField,
                        onValueChange = { typed ->
                            val formatted = formatCpfInput(typed.text)
                            cpfField = TextFieldValue(
                                text = formatted,
                                selection = TextRange(formatted.length)
                            )
                            localError = null
                        },
                        label = { Text("CPF") },
                        placeholder = { Text("000.000.000-00") },
                        leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null) },
                        singleLine = true,
                        isError = normalizeCpf(cpf).length == 11 && !isValidCpf(cpf),
                        supportingText = {
                            if (normalizeCpf(cpf).length == 11 && !isValidCpf(cpf)) {
                                Text("CPF inválido.")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(20.dp),
                        colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        localError = null
                    },
                    label = { Text("E-mail") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(20.dp),
                    colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                    modifier = Modifier.fillMaxWidth()
                )

                if (registerMode) {
                    OutlinedTextField(
                        value = birthDateField,
                        onValueChange = { typed ->
                            val formatted = formatBirthDateInput(typed.text)
                            birthDateField = TextFieldValue(
                                text = formatted,
                                selection = TextRange(formatted.length)
                            )
                            localError = null
                        },
                        label = { Text("Data de nascimento") },
                        placeholder = { Text("DD/MM/AAAA") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        shape = RoundedCornerShape(20.dp),
                        colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { sexMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, loginOutline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = loginSecondary)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sexo: $selectedSexLabel", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }

                        DropdownMenu(
                            expanded = sexMenuExpanded,
                            onDismissRequest = { sexMenuExpanded = false }
                        ) {
                            sexOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sex = option.value
                                        sexMenuExpanded = false
                                        localError = null
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localError = null
                    },
                    label = { Text("Senha") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (registerMode) ImeAction.Next else ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Ocultar senha" else "Mostrar senha"
                            )
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!registerMode) {
                    TextButton(
                        onClick = onForgotPassword,
                        enabled = !state.loading,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            "Esqueci minha senha",
                            color = loginBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (registerMode) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            localError = null
                        },
                        label = { Text("Confirmar senha") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        singleLine = true,
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (confirmPasswordVisible) {
                                        "Ocultar confirmação de senha"
                                    } else {
                                        "Mostrar confirmação de senha"
                                    }
                                )
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = authFieldColors(loginBlue, loginText, loginSecondary, loginOutline),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                }

                if (registerMode) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = legalAccepted, onCheckedChange = { legalAccepted = it })
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Li e concordo com os Termos de Uso e li a Política de Privacidade.",
                                color = loginSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { showLegalDocuments = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    "Ler documentos • Termos ${FinanceAppLegal.TERMS_VERSION}",
                                    color = loginBlue,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (registerMode) {
                            val birthDateIso = birthDateToIso(birthDate)
                            localError = when {
                                name.trim().length < 2 -> "Informe seu nome."
                                email.isBlank() -> "Informe seu e-mail."
                                birthDateIso == null -> "Informe uma data de nascimento válida."
                                sex.isBlank() -> "Selecione o sexo."
                                password.length < 6 -> "A senha deve ter pelo menos 6 caracteres."
                                password != confirmPassword -> "As senhas não coincidem."
                                else -> null
                            }

                            if (localError == null) {
                                if (name.trim().split(Regex("\\s+")).size < 2) {
                                    localError = "Informe seu nome completo."
                                } else if (!isValidCpf(cpf)) {
                                    localError = "Informe um CPF válido."
                                } else {
                                    if (!legalAccepted) {
                                        localError = "Leia e aceite os Termos de Uso e a Política de Privacidade."
                                    } else {
                                        LegalAcceptanceStore.record(context)
                                        onRegister(
                                            name.trim(),
                                            normalizeCpf(cpf),
                                            email.trim(),
                                            password,
                                            birthDateIso!!,
                                            sex
                                        )
                                    }
                                }
                            }
                        } else {
                            onLogin(email.trim(), password)
                        }
                    },
                    enabled = !state.loading &&
                        email.isNotBlank() &&
                        password.isNotBlank() &&
                        (!registerMode || (
                            name.isNotBlank() &&
                                normalizeCpf(cpf).length == 11 &&
                                birthDate.length == 10 &&
                                sex.isNotBlank() &&
                                confirmPassword.isNotBlank() &&
                                legalAccepted
                            )),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = loginBlue,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF8A94A3),
                        disabledContentColor = Color(0xFFE9EDF2)
                    )
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            if (registerMode) "Criar conta" else "Entrar",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }

                if (!registerMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = loginOutline)
                        Text(
                            "ou",
                            color = loginSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = loginOutline)
                    }

                    OutlinedButton(
                        onClick = {
                            if (LegalAcceptanceStore.acceptedCurrent(context)) {
                                onGoogleSignIn()
                            } else {
                                pendingLegalAction = onGoogleSignIn
                            }
                        },
                        enabled = !state.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, loginOutline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = loginText)
                    ) {
                        GoogleGLogo()
                        Spacer(Modifier.width(10.dp))
                        Text("Entrar com Google", fontWeight = FontWeight.SemiBold)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            "Ainda não tem uma conta?",
                            color = loginSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = changeMode, enabled = !state.loading) {
                            Text("Criar conta", color = loginBlue, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val action = { showGuestNotice = true }
                            if (LegalAcceptanceStore.acceptedCurrent(context)) {
                                action()
                            } else {
                                pendingLegalAction = action
                            }
                        },
                        enabled = !state.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = loginBlue.copy(alpha = 0.08f),
                            contentColor = loginBlue
                        )
                    ) {
                        Icon(Icons.Default.PersonOutline, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Continuar sem cadastro",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }

                    if (biometricAvailable && state.biometricQuickLoginAvailable) {
                        TextButton(
                            onClick = {
                                showBiometricPrompt(
                                    context = context,
                                    onSuccess = onBiometricSuccess
                                )
                            },
                            enabled = !state.loading
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = loginSecondary)
                            Spacer(Modifier.width(6.dp))
                            Text("Entrar com biometria", color = loginSecondary)
                        }
                    }
                }

                if (registerMode) {
                    TextButton(onClick = changeMode, enabled = !state.loading) {
                        Text("Já tenho uma conta", color = loginBlue, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(6.dp))
            }
        }
    }

}


@Composable
private fun LargeScreenLogin(
    state: AuthUiState,
    email: String,
    password: String,
    passwordVisible: Boolean,
    biometricAvailable: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onLogin: () -> Unit,
    onForgotPassword: () -> Unit,
    onGoogle: () -> Unit,
    onGuest: () -> Unit,
    onCreateAccount: () -> Unit,
    onBiometric: () -> Unit
) {
    val navy = Color(0xFF061B31)
    val blue = Color(0xFF1478FF)
    val purple = Color(0xFF6D4CFF)
    val text = Color(0xFF10233D)
    val secondary = Color(0xFF6A778C)
    val outline = Color(0xFFD7E0EC)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F8FC))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(34.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .background(Brush.linearGradient(listOf(navy, Color(0xFF0C3569), purple)))
                        .padding(52.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(320.dp)
                            .offset(x = 240.dp, y = (-140).dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                    )
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color.White,
                                shadowElevation = 6.dp,
                                modifier = Modifier.size(62.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.financeapp_mark),
                                    contentDescription = "FinanceApp",
                                    modifier = Modifier.padding(7.dp)
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Text("FinanceApp", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Bem-vindo de volta!", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(18.dp))
                        Text("Acesse sua conta e continue cuidando da sua vida financeira.", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(30.dp))
                        Box(Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(8.dp)).background(blue))
                        Spacer(Modifier.weight(1f))
                        Text("Sua vida financeira, mais simples.", color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 430.dp)
                            .padding(horizontal = 46.dp, vertical = 40.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Entrar", color = text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.displaySmall)
                        Text("Use seus dados para acessar o FinanceApp.", color = secondary, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = onEmailChange,
                            label = { Text("E-mail") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(18.dp),
                            colors = authFieldColors(blue, text, secondary, outline),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            label = { Text("Senha") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            trailingIcon = {
                                IconButton(onClick = onTogglePassword) {
                                    Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            colors = authFieldColors(blue, text, secondary, outline),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = onForgotPassword, enabled = !state.loading, modifier = Modifier.align(Alignment.End)) {
                            Text("Esqueci minha senha", color = blue, fontWeight = FontWeight.SemiBold)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = onLogin,
                            enabled = !state.loading && email.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = blue,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF8A94A3),
                                disabledContentColor = Color(0xFFE9EDF2)
                            )
                        ) {
                            if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                            else Text("Entrar", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f), color = outline)
                            Text("ou", color = secondary, modifier = Modifier.padding(horizontal = 14.dp))
                            HorizontalDivider(Modifier.weight(1f), color = outline)
                        }
                        OutlinedButton(
                            onClick = onGoogle,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, outline)
                        ) {
                            GoogleGLogo()
                            Spacer(Modifier.width(10.dp))
                            Text("Entrar com Google", color = text, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("Ainda não tem uma conta?", color = secondary)
                            TextButton(onClick = onCreateAccount, enabled = !state.loading) { Text("Criar conta", color = blue, fontWeight = FontWeight.Bold) }
                        }
                        OutlinedButton(
                            onClick = onGuest,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = blue.copy(alpha = 0.08f), contentColor = blue)
                        ) {
                            Icon(Icons.Default.PersonOutline, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Continuar sem cadastro", fontWeight = FontWeight.SemiBold)
                        }
                        if (biometricAvailable && state.biometricQuickLoginAvailable) {
                            TextButton(onClick = onBiometric, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = secondary)
                                Spacer(Modifier.width(6.dp))
                                Text("Entrar com biometria", color = secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun authFieldColors(
    accent: Color,
    text: Color,
    secondary: Color,
    outline: Color
) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = outline,
    focusedLabelColor = accent,
    unfocusedLabelColor = secondary,
    focusedTextColor = text,
    unfocusedTextColor = text,
    cursorColor = accent,
    focusedLeadingIconColor = accent,
    unfocusedLeadingIconColor = secondary,
    focusedTrailingIconColor = secondary,
    unfocusedTrailingIconColor = secondary,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White
)

private fun formatBirthDateInput(
    raw: String
): String {
    val digits = raw
        .filter(Char::isDigit)
        .take(8)

    return buildString {
        digits.forEachIndexed { index, char ->
            if (index == 2 || index == 4) {
                append('/')
            }
            append(char)
        }
    }
}

private fun birthDateToIso(
    raw: String
): String? {
    if (raw.length != 10) {
        return null
    }

    return try {
        val date = LocalDate.parse(
            raw,
            DateTimeFormatter.ofPattern("dd/MM/uuuu")
        )

        if (!date.isBefore(LocalDate.now())) {
            null
        } else {
            date.toString()
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun showBiometricPrompt(
    context: Context,
    onSuccess: () -> Unit
) {
    val activity = context as? FragmentActivity ?: return

    val executor =
        androidx.core.content.ContextCompat.getMainExecutor(context)

    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        }
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Acessar Finance App")
        .setSubtitle("Confirme sua identidade")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    prompt.authenticate(info)
}


@Composable
private fun EmailVerificationLinkDialog(
    email: String,
    loading: Boolean,
    error: String?,
    onResend: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!loading) {
                onDismiss()
            }
        },
        title = {
            Text("Confirme seu e-mail")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                Text(
                    "Enviamos um e-mail de confirmação para $email."
                )

                Text(
                    "Abra o e-mail e toque no botão “Confirmar meu e-mail”. Depois, volte ao Finance App e faça login normalmente.",
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )

                Text(
                    "O link é válido por 24 horas.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )

                error?.let {
                    Text(
                        it,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }

                TextButton(
                    onClick = onResend,
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(
                                    16.dp
                                ),
                            strokeWidth = 2.dp
                        )
                        Spacer(
                            Modifier.width(
                                6.dp
                            )
                        )
                    }

                    Text(
                        if (loading) {
                            "Enviando..."
                        } else {
                            "Reenviar e-mail"
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                enabled = !loading
            ) {
                Text("Entendido")
            }
        }
    )
}
