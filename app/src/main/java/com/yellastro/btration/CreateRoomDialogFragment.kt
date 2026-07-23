package com.yellastro.btration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.voice.VoiceFrameDuration
import com.yellastro.btration.voice.VoiceTransportPreference

/**
 * Диалог создания комнаты с выбором transport-ов и отдельной длительностью voice frame для Star/MESHRA.
 */
class CreateRoomDialogFragment : DialogFragment() {

    private lateinit var etRoomName: EditText
    private lateinit var spRoomTransport: Spinner
    private lateinit var spVoiceTransport: Spinner
    private lateinit var spVoiceFrameDuration: Spinner
    private lateinit var btnCancel: Button
    private lateinit var btnCreate: Button
    private lateinit var tvError: TextView
    private lateinit var voiceTransportAdapter: ArrayAdapter<String>
    private val roomTransportModes = RoomTransportMode.values()
    private val voiceTransportModes = VoiceTransportPreference.values()
    private val voiceFrameDurations = VoiceFrameDuration.values()
    private val frameDurationsByRoomMode = mutableMapOf<RoomTransportMode, VoiceFrameDuration>()
    private var lastSelectableVoiceTransportPosition = 0
    private var activeFrameDurationRoomMode: RoomTransportMode? = null

    /**
     * Настраивает стиль диалога до создания окна.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_WalkieTalkie_Dialog)
    }

    /**
     * Создает XML-разметку диалога.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_create_room, container, false)
    }

    /**
     * Настраивает ввод, transport/profile selectors, валидацию и отправку результата.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etRoomName = view.findViewById(R.id.et_room_name)
        spRoomTransport = view.findViewById(R.id.sp_room_transport)
        spVoiceTransport = view.findViewById(R.id.sp_voice_transport)
        spVoiceFrameDuration = view.findViewById(R.id.sp_voice_frame_duration)
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnCreate = view.findViewById(R.id.btn_create)
        tvError = view.findViewById(R.id.tv_error)
        frameDurationsByRoomMode[RoomTransportMode.NEARBY_STAR] = initialNearbyStarFrameDuration()
        frameDurationsByRoomMode[RoomTransportMode.MESHRA] = initialMeshraFrameDuration()
        applyInitialRoomName()
        setupRoomTransportSpinner()
        setupVoiceTransportSpinner()
        setupVoiceFrameDurationSpinner()
        syncVoiceTransportWithRoomMode(showToast = false)
        syncVoiceFrameDurationWithRoomMode()

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnCreate.setOnClickListener {
            val name = etRoomName.text.toString().trim()
            if (name.isEmpty()) {
                tvError.text = "Название канала не может быть пустым"
                tvError.visibility = View.VISIBLE
            } else if (name.length > 20) {
                tvError.text = "Слишком длинное название (макс. 20 символов)"
                tvError.visibility = View.VISIBLE
            } else {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putString(RESULT_ROOM_NAME, name)
                        putString(RESULT_ROOM_TRANSPORT_MODE, selectedRoomTransportMode().prefValue)
                        putString(RESULT_VOICE_TRANSPORT_PREF, selectedVoiceTransportPreference().prefValue)
                        putString(RESULT_VOICE_FRAME_DURATION, selectedVoiceFrameDuration().prefValue)
                    },
                )
                dismiss()
            }
        }
    }

    /**
     * Подставляет последнее использованное имя комнаты в поле ввода и ставит курсор в конец.
     */
    private fun applyInitialRoomName() {
        val initialRoomName = arguments?.getString(ARG_INITIAL_ROOM_NAME).orEmpty()
        if (initialRoomName.isBlank()) {
            return
        }
        etRoomName.setText(initialRoomName)
        etRoomName.setSelection(etRoomName.text.length)
    }

    /**
     * Настраивает список типа комнаты и выставляет сохраненный режим.
     */
    private fun setupRoomTransportSpinner() {
        val initialMode = initialRoomTransportMode()
        spRoomTransport.adapter = createWhiteTextAdapter(roomTransportModes.map { mode -> mode.fullName })
        spRoomTransport.setSelection(roomTransportModes.indexOf(initialMode).coerceAtLeast(0))
        spRoomTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /**
             * Синхронизирует voice transport и сохраненную для типа комнаты длительность фрейма.
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (::voiceTransportAdapter.isInitialized) {
                    syncVoiceTransportWithRoomMode(showToast = true)
                }
                if (::spVoiceFrameDuration.isInitialized && spVoiceFrameDuration.adapter != null) {
                    syncVoiceFrameDurationWithRoomMode()
                }
            }

            /**
             * Ничего не делает, потому что spinner всегда держит выбранный тип комнаты.
             */
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Настраивает список voice transport и блокирует неподдержанные для текущего типа комнаты варианты.
     */
    private fun setupVoiceTransportSpinner() {
        val initialPreference = initialVoiceTransportPreference()
        lastSelectableVoiceTransportPosition = voiceTransportModes.indexOf(initialPreference).coerceAtLeast(0)
        voiceTransportAdapter = createVoiceTransportAdapter()
        spVoiceTransport.adapter = voiceTransportAdapter
        spVoiceTransport.setSelection(lastSelectableVoiceTransportPosition)
        spVoiceTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /**
             * Запоминает последний доступный transport или откатывает выбор недоступной опции.
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPreference = voiceTransportModes[position]
                if (isVoiceTransportAllowedForRoom(selectedPreference, selectedRoomTransportMode())) {
                    lastSelectableVoiceTransportPosition = position
                    return
                }
                showBlockedVoiceTransportToast(selectedPreference)
                spVoiceTransport.setSelection(fallbackVoiceTransportPosition())
            }

            /**
             * Ничего не делает, потому что spinner всегда держит выбранный transport.
             */
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Настраивает выбор длительности Opus-фрейма и запоминает его для текущего типа комнаты.
     */
    private fun setupVoiceFrameDurationSpinner() {
        spVoiceFrameDuration.adapter = createWhiteTextAdapter(
            voiceFrameDurations.map { duration -> duration.fullName },
        )
        activeFrameDurationRoomMode = selectedRoomTransportMode()
        val initialDuration = frameDurationsByRoomMode.getValue(selectedRoomTransportMode())
        spVoiceFrameDuration.setSelection(voiceFrameDurations.indexOf(initialDuration).coerceAtLeast(0))
        spVoiceFrameDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /**
             * Сохраняет выбор в локальном состоянии диалога для текущего типа комнаты.
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val roomMode = activeFrameDurationRoomMode ?: selectedRoomTransportMode()
                voiceFrameDurations.getOrNull(position)?.let { duration ->
                    frameDurationsByRoomMode[roomMode] = duration
                }
            }

            /**
             * Ничего не делает, потому что spinner всегда имеет выбранную длительность.
             */
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Создает adapter voice spinner-а, который визуально и интерактивно блокирует недоступные transport-ы.
     */
    private fun createVoiceTransportAdapter(): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            voiceTransportModes.map { mode -> mode.fullName },
        ) {
            /**
             * Возвращает true только для voice transport-ов, разрешенных выбранным типом комнаты.
             */
            override fun isEnabled(position: Int): Boolean {
                val preference = voiceTransportModes.getOrNull(position) ?: return false
                return isVoiceTransportAllowedForRoom(preference, selectedRoomTransportMode())
            }

            /**
             * Подкрашивает закрытое значение spinner под темную карточку диалога.
             */
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also { itemView ->
                    (itemView as? TextView)?.setTextColor(android.graphics.Color.WHITE)
                }
            }

            /**
             * Затемняет недоступные пункты в выпадающем списке, чтобы Direct не выглядел выбираемым для MESHRA.
             */
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also { itemView ->
                    (itemView as? TextView)?.setTextColor(
                        if (isEnabled(position)) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.GRAY
                        },
                    )
                }
            }
        }.also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    /**
     * Создает spinner adapter с белым текстом выбранного значения на темной карточке.
     */
    private fun createWhiteTextAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            items,
        ) {
            /**
             * Подкрашивает закрытое значение spinner под темную карточку диалога.
             */
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also { itemView ->
                    (itemView as? TextView)?.setTextColor(android.graphics.Color.WHITE)
                }
            }
        }.also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    /**
     * Обновляет voice spinner после смены типа комнаты и уводит MESHRA с Wi-Fi Direct на безопасный fallback.
     */
    private fun syncVoiceTransportWithRoomMode(showToast: Boolean) {
        voiceTransportAdapter.notifyDataSetChanged()
        val selectedPosition = spVoiceTransport.selectedItemPosition
        val selectedPreference = voiceTransportModes.getOrNull(selectedPosition)
            ?: voiceTransportModes[fallbackVoiceTransportPosition()]
        if (isVoiceTransportAllowedForRoom(selectedPreference, selectedRoomTransportMode())) {
            if (selectedPosition in voiceTransportModes.indices) {
                lastSelectableVoiceTransportPosition = selectedPosition
            }
            return
        }
        val fallbackPosition = fallbackVoiceTransportPosition()
        lastSelectableVoiceTransportPosition = fallbackPosition
        spVoiceTransport.setSelection(fallbackPosition)
        if (showToast) {
            showBlockedVoiceTransportToast(selectedPreference)
        }
    }

    /**
     * Запоминает длительность предыдущего типа комнаты и показывает отдельный выбор нового типа.
     */
    private fun syncVoiceFrameDurationWithRoomMode() {
        val nextRoomMode = selectedRoomTransportMode()
        activeFrameDurationRoomMode?.let { previousRoomMode ->
            val selectedPosition = spVoiceFrameDuration.selectedItemPosition
            voiceFrameDurations.getOrNull(selectedPosition)?.let { duration ->
                frameDurationsByRoomMode[previousRoomMode] = duration
            }
        }
        activeFrameDurationRoomMode = nextRoomMode
        val nextDuration = frameDurationsByRoomMode.getValue(nextRoomMode)
        val nextPosition = voiceFrameDurations.indexOf(nextDuration).coerceAtLeast(0)
        if (spVoiceFrameDuration.selectedItemPosition != nextPosition) {
            spVoiceFrameDuration.setSelection(nextPosition)
        }
    }

    /**
     * Проверяет, можно ли выбрать voice transport при текущем типе комнаты.
     */
    private fun isVoiceTransportAllowedForRoom(
        preference: VoiceTransportPreference,
        roomTransportMode: RoomTransportMode,
    ): Boolean {
        if (!preference.isSelectable) {
            return false
        }
        return roomTransportMode != RoomTransportMode.MESHRA || preference != VoiceTransportPreference.WIFI_DIRECT
    }

    /**
     * Возвращает позицию fallback voice transport-а: для MESHRA это не-Direct вариант.
     */
    private fun fallbackVoiceTransportPosition(): Int {
        val fallbackPreference = if (selectedRoomTransportMode() == RoomTransportMode.MESHRA) {
            VoiceTransportPreference.NEARBY_CONNECT
        } else {
            voiceTransportModes.getOrNull(lastSelectableVoiceTransportPosition)
                ?.takeIf { preference -> isVoiceTransportAllowedForRoom(preference, selectedRoomTransportMode()) }
                ?: VoiceTransportPreference.WIFI_DIRECT
        }
        return voiceTransportModes.indexOf(fallbackPreference).coerceAtLeast(0)
    }

    /**
     * Показывает причину, по которой выбранный voice transport недоступен.
     */
    private fun showBlockedVoiceTransportToast(preference: VoiceTransportPreference) {
        val message = if (!preference.isSelectable) {
            "Wi-Fi Aware пока не поддерживается"
        } else {
            "Wi-Fi Direct недоступен для MESHRA-комнаты"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Читает начальный тип комнаты из аргументов диалога.
     */
    private fun initialRoomTransportMode(): RoomTransportMode {
        return RoomTransportMode.fromPrefValue(
            arguments?.getString(ARG_INITIAL_ROOM_TRANSPORT_MODE),
        )
    }

    /**
     * Читает начальный voice transport из аргументов диалога.
     */
    private fun initialVoiceTransportPreference(): VoiceTransportPreference {
        return VoiceTransportPreference.fromPrefValue(
            arguments?.getString(ARG_INITIAL_VOICE_TRANSPORT_PREF),
        ).takeIf { preference -> preference.isSelectable } ?: VoiceTransportPreference.WIFI_DIRECT
    }

    /**
     * Читает сохраненную длительность voice frame для Nearby Star из аргументов диалога.
     */
    private fun initialNearbyStarFrameDuration(): VoiceFrameDuration {
        return VoiceFrameDuration.fromPrefValue(
            value = arguments?.getString(ARG_INITIAL_NEARBY_STAR_FRAME_DURATION),
            default = VoiceFrameDuration.MS_10,
        )
    }

    /**
     * Читает сохраненную длительность voice frame для MESHRA из аргументов диалога.
     */
    private fun initialMeshraFrameDuration(): VoiceFrameDuration {
        return VoiceFrameDuration.fromPrefValue(
            value = arguments?.getString(ARG_INITIAL_MESHRA_FRAME_DURATION),
            default = VoiceFrameDuration.MS_20,
        )
    }

    /**
     * Возвращает выбранный поддержанный voice transport для результата создания комнаты.
     */
    private fun selectedVoiceTransportPreference(): VoiceTransportPreference {
        val position = spVoiceTransport.selectedItemPosition.takeIf { it in voiceTransportModes.indices }
            ?: lastSelectableVoiceTransportPosition
        val preference = voiceTransportModes[position]
        return if (isVoiceTransportAllowedForRoom(preference, selectedRoomTransportMode())) {
            preference
        } else {
            voiceTransportModes[fallbackVoiceTransportPosition()]
        }
    }

    /**
     * Возвращает выбранную длительность voice frame и сохраняет ее для текущего типа комнаты.
     */
    private fun selectedVoiceFrameDuration(): VoiceFrameDuration {
        val duration = voiceFrameDurations.getOrNull(spVoiceFrameDuration.selectedItemPosition)
            ?: frameDurationsByRoomMode.getValue(selectedRoomTransportMode())
        frameDurationsByRoomMode[selectedRoomTransportMode()] = duration
        return duration
    }

    /**
     * Возвращает выбранный тип транспорта комнаты.
     */
    private fun selectedRoomTransportMode(): RoomTransportMode {
        val defaultPosition = roomTransportModes.indexOf(RoomTransportMode.MESHRA).coerceAtLeast(0)
        val position = spRoomTransport.selectedItemPosition.takeIf { it in roomTransportModes.indices } ?: defaultPosition
        return roomTransportModes[position]
    }

    /**
     * Делает окно диалога широким и оставляет прозрачный фон вокруг кастомной карточки.
     */
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    /**
     * Константы Fragment Result API для безопасного возврата имени комнаты.
     */
    companion object {
        private const val ARG_INITIAL_ROOM_NAME = "initial_room_name"
        private const val ARG_INITIAL_ROOM_TRANSPORT_MODE = "initial_room_transport_mode"
        private const val ARG_INITIAL_VOICE_TRANSPORT_PREF = "initial_voice_transport_pref"
        private const val ARG_INITIAL_NEARBY_STAR_FRAME_DURATION = "initial_nearby_star_frame_duration"
        private const val ARG_INITIAL_MESHRA_FRAME_DURATION = "initial_meshra_frame_duration"

        /**
         * Создает диалог с transport-настройками и отдельными сохраненными frame duration для Star/MESHRA.
         */
        fun newInstance(
            initialRoomName: String,
            initialRoomTransportMode: RoomTransportMode,
            initialVoiceTransportPreference: VoiceTransportPreference,
            initialNearbyStarFrameDuration: VoiceFrameDuration,
            initialMeshraFrameDuration: VoiceFrameDuration,
        ): CreateRoomDialogFragment {
            return CreateRoomDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_ROOM_NAME, initialRoomName)
                    putString(ARG_INITIAL_ROOM_TRANSPORT_MODE, initialRoomTransportMode.prefValue)
                    putString(ARG_INITIAL_VOICE_TRANSPORT_PREF, initialVoiceTransportPreference.prefValue)
                    putString(ARG_INITIAL_NEARBY_STAR_FRAME_DURATION, initialNearbyStarFrameDuration.prefValue)
                    putString(ARG_INITIAL_MESHRA_FRAME_DURATION, initialMeshraFrameDuration.prefValue)
                }
            }
        }

        /**
         * Ключ результата создания комнаты.
         */
        const val REQUEST_KEY = "create_room_request"

        /**
         * Ключ имени комнаты внутри результата.
         */
        const val RESULT_ROOM_NAME = "room_name"

        /**
         * Ключ выбранного типа комнаты внутри результата.
         */
        const val RESULT_ROOM_TRANSPORT_MODE = "room_transport_mode"

        /**
         * Ключ выбранного voice transport внутри результата.
         */
        const val RESULT_VOICE_TRANSPORT_PREF = "voice_transport_pref"

        /**
         * Ключ выбранной длительности voice frame внутри результата.
         */
        const val RESULT_VOICE_FRAME_DURATION = "voice_frame_duration"
    }
}
