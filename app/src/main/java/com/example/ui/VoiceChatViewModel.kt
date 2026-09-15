package com.example.ui

/**
 * VoiceChatViewModel
 *
 * Backward-compatible subclass extending [LiveVoiceViewModel],
 * which serves as the core state machine for IDLE, LISTENING, PROCESSING,
 * and SPEAKING states with runtime API key validation.
 */
class VoiceChatViewModel : LiveVoiceViewModel()
