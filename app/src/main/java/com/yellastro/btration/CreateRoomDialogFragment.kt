package com.yellastro.btration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * Диалог создания комнаты, возвращающий имя комнаты через Fragment Result API.
 */
class CreateRoomDialogFragment : DialogFragment() {

    private lateinit var etRoomName: EditText
    private lateinit var btnCancel: Button
    private lateinit var btnCreate: Button
    private lateinit var tvError: TextView

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
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnCreate = view.findViewById(R.id.btn_create)
        tvError = view.findViewById(R.id.tv_error)

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
                    },
                )
                dismiss()
            }
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
        /**
         * Ключ результата создания комнаты.
         */
        const val REQUEST_KEY = "create_room_request"

        /**
         * Ключ имени комнаты внутри результата.
         */
        const val RESULT_ROOM_NAME = "room_name"
    }
}
