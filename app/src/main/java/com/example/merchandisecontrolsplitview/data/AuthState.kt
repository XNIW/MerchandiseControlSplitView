package com.example.merchandisecontrolsplitview.data

/**
 * Stato di autenticazione dell'app (task 011).
 *
 * Source of truth unica, esposta come [kotlinx.coroutines.flow.StateFlow] da
 * [SupabaseAuthManager]. ViewModel e componenti remoti osservano questo stato
 * senza mantenere copie o flag duplicati.
 *
 * - [Checking]: bootstrap o restore sessione in corso; la UI non deve forzare
 *   login ne' mostrare contenuti cloud-bound.
 * - [SignedOut]: nessuna sessione attiva; i flussi locali restano disponibili.
 * - [SignedIn]: sessione Supabase valida con identita' utente.
 * - [ErrorRecoverable]: errore recuperabile; il prossimo tentativo e' avviato
 *   dall'utente, non da auto-retry.
 */
sealed interface AuthState {

    /** Bootstrap o restore sessione in corso. */
    data object Checking : AuthState

    /** Nessuna sessione attiva. */
    data object SignedOut : AuthState

    /** Sessione Supabase valida. */
    data class SignedIn(val userId: String, val email: String?) : AuthState

    /** Errore recuperabile (retry manuale). */
    data class ErrorRecoverable(val message: String) : AuthState
}
