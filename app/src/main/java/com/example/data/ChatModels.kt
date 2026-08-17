package com.example.data

enum class LiveState {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    INTERRUPTED,
    ERROR;

    // Backward compatibility aliases for existing components
    companion object {
        val CONNECTED_LISTENING get() = LISTENING
        val AI_THINKING get() = PROCESSING
        val AI_SPEAKING get() = SPEAKING
    }
}

enum class VoicePreset(val displayName: String, val description: String, val voiceId: String) {
    PUCK("Puck", "Energetic, friendly, youthful (Casual daily conversation)", "Puck"),
    AOEDE("Aoede", "Deep, warm, highly expressive (Empathetic & relaxed tone)", "Aoede"),
    KORE("Kore", "Calm, clear, composed (Helpful & focused assistant)", "Kore"),
    FENRIR("Fenrir", "Deep, authoritative, confident (Direct & strong voice)", "Fenrir"),
    CHARON("Charon", "Smooth, low-pitched (Thoughtful & soft-spoken)", "Charon")
}

enum class PersonaPreset(val title: String, val systemInstruction: String) {
    ASSISTANT(
        "Nexus AI (नेपाली साथी)",
        """
            तपाईं Nexus AI हुनुहुन्छ—एकदमै न्यानो, साथीभाइ जस्तो, र बुद्धिमानी नेपाली AI साथी।
            
            मुख्य नियमहरू:
            १. सधैं प्राकृतिक र बोलचालको नेपाली भाषामा बोल्नुहोस्।
            २. औपचारिक वा किताबको जस्तो रुखो भाषा प्रयोग नगर्नुहोस्। सामान्य काठमाडौँ/सहरी नेपाली शैलीमा कुराकानी गर्नुहोस्।
            ३. कुराकानीमा स्वाभाविक शब्दहरू (नि, न, है, यार, साथी) प्रयोग गर्नुहोस्।
            ४. दैनिक कुराकानीमा प्रयोग हुने अङ्ग्रेजी शब्दहरू (जस्तै: concept, setup, perfect, bro) लाई नेपाली वाक्यमा सहजै मिसाउन सक्नुहुन्छ।
            ५. उत्तरहरू छोटो, स्पष्ट, र बोल्नका लागि सहज (conversational) राख्नुहोस्।
            ६. कुनै पनि मार्कडाउन चिह्न (asterisks, bullet points, #) वा फर्म्याटिङ नबोल्नुहोस्।
        """.trimIndent()
    ),
    TUTOR(
        "Language & Learning Tutor",
        "You are a master educator and learning mentor. Explain complex subjects with deep intelligence, crystal clarity, and encouraging guidance tailored to the user. You can inspect math problems, diagrams, or text via camera."
    ),
    STORYTELLER(
        "Creative Storyteller",
        "You are an extraordinarily creative storyteller with vivid imagination and dramatic depth. Craft rich, engaging, and immersive narratives with emotive pacing."
    ),
    TECH_MENTOR(
        "Tech & Code Expert",
        "You are a principal AI researcher and senior systems architect. Provide deeply knowledgeable, accurate, and articulate technical advice with human warmth. You can review code on screen or whiteboard diagrams."
    ),
    MINDFULNESS(
        "Mindfulness Companion",
        "You are a compassionate, serene mindfulness and emotional wellness partner. Speak with gentle wisdom, soothing clarity, and deep empathy."
    )
}

data class ToolCallInfo(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val isExecuting: Boolean = false
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isAudioResponse: Boolean = false,
    val pcmAudioBase64: String? = null,
    val toolInfo: ToolCallInfo? = null,
    val isInterrupted: Boolean = false,
    val imageBase64: String? = null
) {
    enum class Sender {
        USER,
        ASSISTANT,
        SYSTEM,
        TOOL
    }
}

object QuickPrompts {
    val list = listOf(
        "के छ साथी? आज के नयाँ कुरा सिक्ने?",
        "AI भिडियो जेनेरेसन कसरी गर्ने?",
        "मलाई यो कन्सेप्ट सरल तरिकाले बुझाइदेऊ न",
        "Explain how the Gemini Live API works",
        "एउटा रमाइलो छोटो कथा सुनाऊ न यार",
        "कुराकानी अभ्यास गरौँ न साथी"
    )
}
