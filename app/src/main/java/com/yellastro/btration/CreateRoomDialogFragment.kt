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
import com.yellastro.btration.voice.VoiceTransportPreference

/**
 * Диалог создания комнаты с предзаполнением последнего имени, выбором voice transport и возвратом результата через Fragment Result API.
 */
class CreateRoomDialogFragment : DialogFragment() {

    private lateinit var etRoomName: EditText
    private lateinit var spVoiceTransport: Spinner
    private lateinit var btnCancel: Button
    private lateinit var btnCreate: Button
    private lateinit var tvError: TextView
    private val voiceTransportModes = VoiceTransportPreference.values()
    private var lastSelectableVoiceTransportPosition = 0

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
     * Настраивает ввод имени комнаты, валидацию и отправку результата.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etRoomName = view.findViewById(R.id.et_room_name)
        spVoiceTransport = view.findViewById(R.id.sp_voice_transport)
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnCreate = view.findViewById(R.id.btn_create)
        tvError = view.findViewById(R.id.tv_error)
        applyInitialRoomName()
        setupVoiceTransportSpinner()

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
                        putString(RESULT_VOICE_TRANSPORT_PREF, selectedVoiceTransportPreference().prefValue)
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
     * Настраивает список voice transport и блокирует пока неподдержанные варианты.
     */
    private fun setupVoiceTransportSpinner() {
        val initialPreference = initialVoiceTransportPreference()
        lastSelectableVoiceTransportPosition = voiceTransportModes.indexOf(initialPreference).coerceAtLeast(0)
        spVoiceTransport.adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            voiceTransportModes.map { mode -> mode.fullName },
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
        spVoiceTransport.setSelection(lastSelectableVoiceTransportPosition)
        spVoiceTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /**
             * Запоминает последний поддержанный transport или откатывает выбор будущей Wi-Fi Aware опции.
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPreference = voiceTransportModes[position]
                if (selectedPreference.isSelectable) {
                    lastSelectableVoiceTransportPosition = position
                    return
                }
                Toast.makeText(requireContext(), "Wi-Fi Aware пока не поддерживается", Toast.LENGTH_SHORT).show()
                spVoiceTransport.setSelection(lastSelectableVoiceTransportPosition)
            }

            /**
             * Ничего не делает, потому что spinner всегда держит выбранный transport.
             */
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
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
     * Возвращает выбранный поддержанный voice transport для результата создания комнаты.
     */
    private fun selectedVoiceTransportPreference(): VoiceTransportPreference {
        val position = spVoiceTransport.selectedItemPosition.takeIf { it in voiceTransportModes.indices }
            ?: lastSelectableVoiceTransportPosition
        val preference = voiceTransportModes[position]
        return if (preference.isSelectable) {
            preference
        } else {
            voiceTransportModes[lastSelectableVoiceTransportPosition]
        }
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
        private const val ARG_INITIAL_VOICE_TRANSPORT_PREF = "initial_voice_transport_pref"

        /**
         * Создает диалог с начальным названием комнаты и сохраненным voice transport.
         */
        fun newInstance(
            initialRoomName: String,
            initialVoiceTransportPreference: VoiceTransportPreference,
        ): CreateRoomDialogFragment {
            return CreateRoomDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_ROOM_NAME, initialRoomName)
                    putString(ARG_INITIAL_VOICE_TRANSPORT_PREF, initialVoiceTransportPreference.prefValue)
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
         * Ключ выбранного voice transport внутри результата.
         */
        const val RESULT_VOICE_TRANSPORT_PREF = "voice_transport_pref"
    }
}
