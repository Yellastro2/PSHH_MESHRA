package com.yellastro.btration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction
import com.yellastro.btration.ui.room.ChatMessageUi
import com.yellastro.btration.ui.room.MemberUi
import com.yellastro.btration.ui.room.RoomUiState
import com.yellastro.btration.ui.room.RoomViewModel
import kotlinx.coroutines.launch

/**
 * Экран комнаты, связанный с RoomViewModel, списком участников, текстовым чатом и PTT-кнопкой.
 */
class RoomFragment : Fragment() {
    private val viewModel: RoomViewModel by viewModels {
        (requireActivity().application as BtRationApplication).appContainer.roomViewModelFactory()
    }

    private lateinit var tvChannelTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var rvMessages: RecyclerView
    private lateinit var rvMembers: RecyclerView
    private lateinit var tvRoomError: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton

    private lateinit var btnPushToTalk: View
    private lateinit var tvTransmissionStatus: TextView
    private lateinit var viewWave1: View
    private lateinit var viewWave2: View
    private lateinit var viewWave3: View

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var memberAdapter: MemberAdapter

    private var isTransmitting = false
    private var latestState = RoomUiState()
    private var closedHandled = false
    private var handledErrorAction: RoomRuntimeErrorAction? = null
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    /**
     * Фабрика RoomFragment без аргументов: состояние комнаты берется из RoomViewModel.
     */
    companion object {
        /**
         * Создает экран текущей активной комнаты.
         */
        fun newInstance(): RoomFragment {
            return RoomFragment()
        }
    }

    /**
     * Регистрирует launcher runtime-permission микрофона для PTT.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordAudioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (!isGranted) {
                Toast.makeText(requireContext(), "Без микрофона голосовая передача не работает", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Создает XML-разметку комнаты.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_room, container, false)
    }

    /**
     * Настраивает списки участников, чат, кнопки и PTT-визуализацию.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvChannelTitle = view.findViewById(R.id.tv_channel_title)
        btnBack = view.findViewById(R.id.btn_back)
        rvMessages = view.findViewById(R.id.rv_messages)
        rvMembers = view.findViewById(R.id.rv_members)
        tvRoomError = view.findViewById(R.id.tv_room_error)
        etMessage = view.findViewById(R.id.et_message)
        btnSend = view.findViewById(R.id.btn_send)

        btnPushToTalk = view.findViewById(R.id.btn_push_to_talk)
        tvTransmissionStatus = view.findViewById(R.id.tv_transmission_status)
        viewWave1 = view.findViewById(R.id.view_wave_1)
        viewWave2 = view.findViewById(R.id.view_wave_2)
        viewWave3 = view.findViewById(R.id.view_wave_3)

        messageAdapter = MessageAdapter()
        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = messageAdapter

        memberAdapter = MemberAdapter()
        rvMembers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvMembers.adapter = memberAdapter

        btnBack.setOnClickListener {
            if (latestState.isHost) {
                showCloseRoomConfirmationDialog()
            } else {
                viewModel.onLeaveClicked()
            }
        }

        btnSend.setOnClickListener {
            viewModel.onSendClicked()
        }

        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onSendClicked()
                true
            } else {
                false
            }
        }

        etMessage.addTextChangedListener(
            object : android.text.TextWatcher {
                /**
                 * Не используется: ViewModel получает уже измененный текст в afterTextChanged.
                 */
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                /**
                 * Не используется: ViewModel получает уже измененный текст в afterTextChanged.
                 */
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                /**
                 * Передает актуальный текст ввода во ViewModel.
                 */
                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.onMessageChanged(s?.toString().orEmpty())
                }
            },
        )

        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTransmission()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTransmission()
                    true
                }
                else -> false
            }
        }

        collectUiState()
    }

    /**
     * Останавливает передачу микрофона при уничтожении view комнаты.
     */
    override fun onDestroyView() {
        stopTransmission()
        super.onDestroyView()
    }

    /**
     * Подписывается на состояние комнаты.
     */
    private fun collectUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::renderState)
            }
        }
    }

    /**
     * Отрисовывает участников, чат, ошибку комнаты, поле ввода, PTT-состояние и закрытие комнаты.
     */
    private fun renderState(state: RoomUiState) {
        latestState = state
        tvChannelTitle.text = state.roomName.ifBlank { "ROOM" }
        btnSend.isEnabled = state.canSend
        btnSend.alpha = if (state.canSend) 1.0f else 0.45f
        tvRoomError.text = state.errorMessage.orEmpty()
        tvRoomError.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        showErrorActionIfNeeded(state.errorAction)
        if (etMessage.text.toString() != state.inputText) {
            etMessage.setText(state.inputText)
            etMessage.setSelection(state.inputText.length)
        }
        memberAdapter.submitItems(state.members)
        val oldMessageCount = messageAdapter.itemCount
        messageAdapter.submitItems(state.messages)
        if (state.messages.size > oldMessageCount) {
            rvMessages.scrollToPosition(state.messages.lastIndex)
        }
        if (state.isClosed && !closedHandled) {
            closedHandled = true
            (activity as? MainActivity)?.popBackStack()
        }
        if (!state.isTalking && isTransmitting) {
            stopTransmission()
        }
    }

    /**
     * Просит хоста подтвердить выход, потому что выход закрывает комнату для всех участников.
     */
    private fun showCloseRoomConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Закрыть комнату?")
            .setMessage("Если вы выйдете, комната будет закрыта для всех участников.")
            .setPositiveButton("Закрыть") { _, _ ->
                viewModel.onCloseRoomClicked()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Показывает одноразовый диалог с действием для исправления runtime-ошибки.
     */
    private fun showErrorActionIfNeeded(action: RoomRuntimeErrorAction?) {
        if (action == null) {
            handledErrorAction = null
            return
        }
        if (handledErrorAction == action) {
            return
        }
        handledErrorAction = action
        when (action) {
            RoomRuntimeErrorAction.OPEN_LOCATION_SETTINGS -> showLocationSettingsDialog()
        }
    }

    /**
     * Предлагает включить системную геолокацию, без которой Nearby advertising не стартует.
     */
    private fun showLocationSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Геолокация выключена")
            .setMessage("Для работы Nearby-комнаты нужно включить геолокацию устройства. BtRation не читает GPS-координаты.")
            .setPositiveButton("Включить") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    /**
     * Включает визуальное состояние передачи и стартует ViewModel-команду микрофона.
     */
    private fun startTransmission() {
        if (isTransmitting || !latestState.canTalk) return
        if (!hasRecordAudioPermission()) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isTransmitting = true
        viewModel.onMicPressed()

        tvTransmissionStatus.text = "ПЕРЕДАЧА..."
        tvTransmissionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light))
        btnPushToTalk.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()

        viewWave1.visibility = View.VISIBLE
        viewWave2.visibility = View.VISIBLE
        viewWave3.visibility = View.VISIBLE

    }

    /**
     * Выключает визуальное состояние передачи и останавливает ViewModel-команду микрофона.
     */
    private fun stopTransmission() {
        if (!isTransmitting) return
        isTransmitting = false
        viewModel.onMicReleased()

        tvTransmissionStatus.text = "УДЕРЖИВАЙТЕ ДЛЯ СВЯЗИ"
        tvTransmissionStatus.setTextColor(requireContext().getColor(android.R.color.white))
        btnPushToTalk.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

        viewWave1.visibility = View.GONE
        viewWave2.visibility = View.GONE
        viewWave3.visibility = View.GONE

    }

    /**
     * Проверяет runtime-permission микрофона перед стартом AudioRecord.
     */
    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Адаптер списка сообщений с разными layout для своих и чужих сообщений.
     */
    private class MessageAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<ChatMessageUi>()

        private val TYPE_ME = 1
        private val TYPE_OTHER = 2

        /**
         * Заменяет список сообщений и перерисовывает RecyclerView.
         */
        fun submitItems(newItems: List<ChatMessageUi>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        /**
         * Выбирает тип карточки сообщения.
         */
        override fun getItemViewType(position: Int): Int {
            val item = items[position]
            return if (item.isOwn) TYPE_ME else TYPE_OTHER
        }

        /**
         * Создает ViewHolder для выбранного типа сообщения.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_ME -> {
                    val view = inflater.inflate(R.layout.item_message_me, parent, false)
                    MessageViewHolder(view)
                }
                else -> {
                    val view = inflater.inflate(R.layout.item_message_other, parent, false)
                    MessageViewHolder(view)
                }
            }
        }

        /**
         * Заполняет текст, автора и время сообщения.
         */
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is MessageViewHolder) {
                holder.tvSender.text = item.senderName
                holder.tvTime.text = item.timeText
                holder.tvText.text = item.text
            }
        }

        /**
         * Возвращает количество сообщений в текущей комнате.
         */
        override fun getItemCount(): Int = items.size

        /**
         * ViewHolder обычного сообщения.
         */
        class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSender: TextView = view.findViewById(R.id.tv_sender_name)
            val tvTime: TextView = view.findViewById(R.id.tv_message_time)
            val tvText: TextView = view.findViewById(R.id.tv_message_text)
        }
    }

    /**
     * Адаптер горизонтального списка участников.
     */
    private class MemberAdapter :
        RecyclerView.Adapter<MemberAdapter.ViewHolder>() {
        private val items = mutableListOf<MemberUi>()

        /**
         * ViewHolder участника комнаты.
         */
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_member_name)
            val tvStatus: TextView = view.findViewById(R.id.tv_member_status)
            val viewIndicator: View = view.findViewById(R.id.view_talking_indicator)
            val ivMuted: View = view.findViewById(R.id.iv_muted_icon)
        }

        /**
         * Заменяет список участников и перерисовывает RecyclerView.
         */
        fun submitItems(newItems: List<MemberUi>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        /**
         * Создает карточку участника из XML-разметки.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false)
            return ViewHolder(view)
        }

        /**
         * Заполняет имя, роль и визуальное состояние участника.
         */
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val member = items[position]
            holder.tvName.text = member.name

            val subtitle = if (member.isSelf) "Вы" else "Участник"
            holder.tvStatus.text = subtitle

            if (member.isTalking) {
                holder.viewIndicator.setBackgroundResource(R.drawable.bg_dot_talking)
                holder.viewIndicator.visibility = View.VISIBLE
            } else {
                holder.viewIndicator.setBackgroundResource(R.drawable.bg_dot_idle)
                holder.viewIndicator.visibility = View.VISIBLE
            }

            holder.ivMuted.visibility = View.GONE
        }

        /**
         * Возвращает количество участников в текущей комнате.
         */
        override fun getItemCount(): Int = items.size
    }
}
