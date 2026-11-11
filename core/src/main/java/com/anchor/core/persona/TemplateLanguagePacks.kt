// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

/**
 * Localized template pack contract for offline triggers and notifications (Blueprint 41 §3.7 / Roadmap P1.1 Item 10).
 */
interface TemplatePack {
    val languageCode: String
    val displayName: String
    fun getTemplates(trigger: TriggerType, tone: PersonaTone): List<String>?
}

/**
 * Default English template pack.
 */
object EnglishTemplatePack : TemplatePack {
    override val languageCode: String = "en"
    override val displayName: String = "English"

    override fun getTemplates(trigger: TriggerType, tone: PersonaTone): List<String>? {
        return TemplateEngine.TEMPLATES[trigger to tone]
    }
}

/**
 * Spanish localized template pack.
 */
object SpanishTemplatePack : TemplatePack {
    override val languageCode: String = "es"
    override val displayName: String = "Español"

    private val SPANISH_TEMPLATES = mapOf(
        // REMINDER_START
        (TriggerType.REMINDER_START to PersonaTone.WARM) to listOf(
            "Es hora de empezar: {title}.",
            "Empezando ahora: {title}. ¡Tú puedes!",
            "¿Listo para empezar {title}?"
        ),
        (TriggerType.REMINDER_START to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "El reloj avanza para {title}. A moverse.",
            "Hora de inicio para {title}. Sin distracciones.",
            "{title} empieza ahora."
        ),
        (TriggerType.REMINDER_START to PersonaTone.STRUCTURED) to listOf(
            "Inicio programado: {title}.",
            "Iniciando {title} según lo previsto.",
            "Acción comenzando ahora: {title}."
        ),
        (TriggerType.REMINDER_START to PersonaTone.CASUAL) to listOf(
            "¡Hola, es hora de {title}!",
            "{title} es ahora. ¡Vamos!",
            "Es hora de {title}."
        ),
        (TriggerType.REMINDER_START to PersonaTone.MINIMAL) to listOf(
            "Inicio: {title}.",
            "Ahora: {title}.",
            "{title}."
        ),

        // REMINDER_CHECK
        (TriggerType.REMINDER_CHECK to PersonaTone.WARM) to listOf(
            "¿Cómo va {title}?",
            "Revisión rápida de {title} — ¡espero que vaya muy bien!",
            "¿Buen progreso con {title}?"
        ),
        (TriggerType.REMINDER_CHECK to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "¿Ya empezaste {title} o sigues viendo memes?",
            "¿Cómo vamos con {title}?",
            "Estado de {title}."
        ),
        (TriggerType.REMINDER_CHECK to PersonaTone.STRUCTURED) to listOf(
            "Control de progreso: {title}.",
            "Revisión de hito para {title}.",
            "Actualización requerida: {title}."
        ),
        (TriggerType.REMINDER_CHECK to PersonaTone.CASUAL) to listOf(
            "¿Cómo va {title}?",
            "Mensaje rápido sobre {title}.",
            "¿Avanzando con {title}?"
        ),
        (TriggerType.REMINDER_CHECK to PersonaTone.MINIMAL) to listOf(
            "Estado: {title}?",
            "Revisar: {title}."
        ),

        // REMINDER_DEADLINE
        (TriggerType.REMINDER_DEADLINE to PersonaTone.WARM) to listOf(
            "{title} vence ahora.",
            "Fecha límite alcanzada para {title}. ¿Necesitas ayuda?",
            "Recordatorio: {title} vence ahora."
        ),
        (TriggerType.REMINDER_DEADLINE to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "{title} vence ahora mismo. Tiempo cumplido.",
            "Llegó el límite para {title}.",
            "{title} venció. A terminarlo."
        ),
        (TriggerType.REMINDER_DEADLINE to PersonaTone.STRUCTURED) to listOf(
            "Fecha límite: {title}.",
            "Plazo cumplido para {title}."
        ),
        (TriggerType.REMINDER_DEADLINE to PersonaTone.CASUAL) to listOf(
            "¡{title} vence ahora!",
            "Atención, la fecha límite de {title} es ya."
        ),
        (TriggerType.REMINDER_DEADLINE to PersonaTone.MINIMAL) to listOf(
            "Límite: {title}.",
            "Vence: {title}."
        ),

        // NUDGE_OVERDUE
        (TriggerType.NUDGE_OVERDUE to PersonaTone.WARM) to listOf(
            "Hola, {title} sigue pendiente. ¿Quieres resolverlo hoy?",
            "{title} está retrasado. Sin estrés, un paso a la vez."
        ),
        (TriggerType.NUDGE_OVERDUE to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "{title} sigue retrasado. ¿Lo hacemos hoy?",
            "{title} no se va a terminar solo."
        ),
        (TriggerType.NUDGE_OVERDUE to PersonaTone.STRUCTURED) to listOf(
            "Elemento vencido: {title}.",
            "Atención requerida para {title}."
        ),
        (TriggerType.NUDGE_OVERDUE to PersonaTone.CASUAL) to listOf(
            "Oye, {title} sigue en tu lista.",
            "Recordatorio rápido sobre {title}."
        ),
        (TriggerType.NUDGE_OVERDUE to PersonaTone.MINIMAL) to listOf(
            "Vencido: {title}."
        ),

        // CHECKIN_MORNING
        (TriggerType.CHECKIN_MORNING to PersonaTone.WARM) to listOf(
            "¡Buenos días! ¿Listo para comenzar?",
            "Buenos días, {name}. Hagamos de hoy un gran día.",
            "¡Hola! Tu agenda de la mañana está lista."
        ),
        (TriggerType.CHECKIN_MORNING to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "Buenos días. El café está listo, la lista también.",
            "Arriba. El día ya empezó."
        ),
        (TriggerType.CHECKIN_MORNING to PersonaTone.STRUCTURED) to listOf(
            "Resumen matutino preparado.",
            "Buenos días. Plan operativo del día listo."
        ),
        (TriggerType.CHECKIN_MORNING to PersonaTone.CASUAL) to listOf(
            "¡Buenos días! ¿Qué tenemos para hoy?",
            "Hola, buenos días. Vamos con calma."
        ),
        (TriggerType.CHECKIN_MORNING to PersonaTone.MINIMAL) to listOf(
            "Buenos días.",
            "Plan de hoy."
        ),

        // CHECKIN_EVENING
        (TriggerType.CHECKIN_EVENING to PersonaTone.WARM) to listOf(
            "Buenas noches. ¿Cómo estuvo el día?",
            "Fin del día. Buen trabajo hoy."
        ),
        (TriggerType.CHECKIN_EVENING to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "Día terminado. Hora de desconectar.",
            "Fin del día. Deja el trabajo para mañana."
        ),
        (TriggerType.CHECKIN_EVENING to PersonaTone.STRUCTURED) to listOf(
            "Cierre del día.",
            "Resumen nocturno completado."
        ),
        (TriggerType.CHECKIN_EVENING to PersonaTone.CASUAL) to listOf(
            "¡Buen trabajo hoy! A descansar.",
            "Día completado."
        ),
        (TriggerType.CHECKIN_EVENING to PersonaTone.MINIMAL) to listOf(
            "Buenas noches.",
            "Cierre del día."
        ),

        // CHECKIN_WEEKLY
        (TriggerType.CHECKIN_WEEKLY to PersonaTone.WARM) to listOf(
            "¡Resumen semanal! Veamos tus logros de esta semana.",
            "Fin de semana: revisemos cómo fue la semana."
        ),
        (TriggerType.CHECKIN_WEEKLY to PersonaTone.DIRECT_SARCASTIC) to listOf(
            "Semana terminada. Veamos las victorias y lo que quedó pendiente.",
            "Balance semanal listo."
        ),
        (TriggerType.CHECKIN_WEEKLY to PersonaTone.STRUCTURED) to listOf(
            "Informe de cadencia semanal.",
            "Revisión de sistemas de fin de semana."
        ),
        (TriggerType.CHECKIN_WEEKLY to PersonaTone.CASUAL) to listOf(
            "¡La semana en revisión! Veamos qué tal.",
            "Hora del repaso semanal."
        ),
        (TriggerType.CHECKIN_WEEKLY to PersonaTone.MINIMAL) to listOf(
            "Revisión semanal.",
            "Resumen semanal."
        )
    )

    override fun getTemplates(trigger: TriggerType, tone: PersonaTone): List<String>? {
        return SPANISH_TEMPLATES[trigger to tone]
    }
}

/**
 * Registry managing localized template packs.
 */
object TemplatePackRegistry {
    private val packs = mutableMapOf<String, TemplatePack>(
        "en" to EnglishTemplatePack,
        "es" to SpanishTemplatePack
    )

    fun registerPack(pack: TemplatePack) {
        packs[pack.languageCode.lowercase()] = pack
    }

    fun getPack(languageCode: String?): TemplatePack? {
        if (languageCode == null) return packs["en"]
        return packs[languageCode.lowercase()]
    }

    fun listPacks(): List<TemplatePack> = packs.values.toList()
}
