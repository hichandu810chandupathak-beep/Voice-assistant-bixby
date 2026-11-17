package com.bixby.assistant

import java.util.Locale

/** Deterministic on-device conversation responses; no API key or network is required. */
object AssistantCore {
    fun reply(raw: String): String? {
        val text = raw.lowercase(Locale.ROOT).trim()
        return when {
            text.contains("चुटकुला") || text.contains("joke") ->
                "एक मोबाइल ने दूसरे से कहा: तुम इतने स्मार्ट कैसे हो? दूसरा बोला: क्योंकि मेरे पास अच्छे ऐप्स हैं! 😄"
            text.contains("आज क्या करूं") || text.contains("आज क्या करूँ") || text.contains("what should i do today") ->
                "आज एक छोटा लक्ष्य चुनो, उसे पूरा करो, थोड़ा आराम करो और किसी अपने से बात करो।"
            text.contains("weather") || text.contains("मौसम") ->
                "मेरे पास live weather data उपलब्ध नहीं है, इसलिए मैं तापमान का अनुमान नहीं लगाऊँगा। मौसम ऐप में ताज़ा forecast देखो।"
            text in listOf("hello", "hi", "hey", "नमस्ते", "हेलो") ->
                "नमस्ते! मैं Bixby हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?"
            text.contains("thank") || text.contains("धन्यवाद") || text.contains("शुक्रिया") -> "आपका स्वागत है! 😊"
            text.contains("who are you") || text.contains("तुम कौन हो") -> "मैं Bixby हूँ — आपके डिवाइस पर चलने वाला निजी voice assistant."
            text.contains("how are you") || text.contains("कैसे हो") -> "मैं तैयार हूँ। आप कैसे हैं?"
            else -> null
        }
    }
}
