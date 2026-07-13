package com.yellastro.btration

import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yellastro.btration.domain.runtime.RoomRuntimeNotice
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction
import com.yellastro.btration.ui.room.ChatMessageUi
import com.yellastro.btration.ui.room.MemberUi
import com.yellastro.btration.ui.room.RoomUiState
import com.yellastro.btration.ui.room.RoomViewModel
import kotlinx.coroutines.launch

/**
 * Экран комнаты с настройками транспорта, direct-аудио статусом, connecting-overlay, чатом, PTT и очисткой клавиатуры.
 */
class RoomFragment : Fragment() {
    private val viewModel: RoomViewModel by viewModels {
        (requireActivity().application as BtRationApplication).appContainer.roomViewModelFactory()
    }

    private lateinit var tvChannelTitle: TextView
    private lateinit var tvChannelStatus: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var ivRoomSettings: ImageView
    private lateinit var rvMessages: RecyclerView
    private lateinit var rvMembers: RecyclerView
    private lateinit var tvRoomError: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var layoutChatInput: View
    private lateinit var layoutConnectingOverlay: View

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
    private var lastShownNoticeId = Long.MIN_VALUE
    private var lastShownSnackbarMessage: String? = null
    private var pushToTalkSizeAnimator: ValueAnimator? = null
    private var pushToTalkTargetSize = 0
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    /**
     * Фабрика RoomFragment без аргументов: состояние комнаты берется из RoomViewModel.
     */
    companion object {
        private const val MENU_GROUP_ROOM_SETTINGS = 0
        private const val MENU_ITEM_VOICE_TRANSPORT = 1
        private const val MENU_ORDER_VOICE_TRANSPORT = 0

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
     * Настраивает списки участников, чат, меню комнаты, PTT-визуализацию и размер кнопки микрофона при IME.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (requireActivity() as MainActivity)
                    .keyboardVisible
                    .collect(::resizePushToTalkForKeyboard)
            }
        }

        tvChannelTitle = view.findViewById(R.id.tv_channel_title)
        tvChannelStatus = view.findViewById(R.id.tv_channel_status)
        btnBack = view.findViewById(R.id.btn_back)
        ivRoomSettings = view.findViewById(R.id.iv_room_settings)
        rvMessages = view.findViewById(R.id.rv_messages)
        rvMembers = view.findViewById(R.id.rv_members)
        tvRoomError = view.findViewById(R.id.tv_room_error)
        etMessage = view.findViewById(R.id.et_message)
        btnSend = view.findViewById(R.id.btn_send)
        layoutChatInput = view.findViewById(R.id.layout_chat_input)
        layoutConnectingOverlay = view.findViewById(R.id.layout_connecting_overlay)

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

        ivRoomSettings.setOnClickListener {
            showRoomSettingsMenu()
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
        collectNotices()

    }

    /**
     * Анимирует реальный layout-размер PTT-кнопки: с клавиатурой она маленькая, без клавиатуры большая.
     */
    private fun resizePushToTalkForKeyboard(keyboardVisible: Boolean) {
        Log.d(
            "Keyboard",
            "visible=$keyboardVisible width=${btnPushToTalk.width}"
        )
        val targetSize = resources.getDimensionPixelSize(
            if (keyboardVisible) {
                R.dimen.micro_size_small
            } else {
                R.dimen.micro_size_big
            }
        )

        btnPushToTalk.post {
            pushToTalkSizeAnimator?.cancel()

            val startSize = btnPushToTalk.width

            if (startSize <= 0 || startSize == targetSize) {
                btnPushToTalk.updateLayoutParams {
                    width = targetSize
                    height = targetSize
                }
                return@post
            }

            pushToTalkSizeAnimator = ValueAnimator.ofInt(startSize, targetSize).apply {
                duration = 180L

                addUpdateListener {
                    val size = it.animatedValue as Int

                    btnPushToTalk.updateLayoutParams {
                        width = size
                        height = size
                    }
                }

                start()
            }
        }
    }

    /**
     * Показывает выпадающее меню настроек комнаты с read-only текущим voice transport.
     */
    private fun showRoomSettingsMenu() {
        val popupMenu = PopupMenu(requireContext(), ivRoomSettings)
        val voiceTransportItem = popupMenu.menu.add(
            MENU_GROUP_ROOM_SETTINGS,
            MENU_ITEM_VOICE_TRANSPORT,
            MENU_ORDER_VOICE_TRANSPORT,
            "транспорт голоса: ${latestState.roomVoiceTransportPreference.shortName}",
        )
        voiceTransportItem.isEnabled = false
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ITEM_VOICE_TRANSPORT -> true

                else -> false
            }
        }
        popupMenu.show()
    }

    /**
     * Скрывает клавиатуру при уходе с экрана комнаты, чтобы IME не оставалась поверх следующего экрана.
     */
    override fun onPause() {
        hideMessageKeyboard()
        super.onPause()
    }

    /**
     * Останавливает передачу микрофона, анимацию PTT-размера и скрывает клавиатуру при уничтожении view комнаты.
     */
    override fun onDestroyView() {
        stopTransmission()
        pushToTalkSizeAnimator?.cancel()
        pushToTalkSizeAnimator = null
        pushToTalkTargetSize = 0
        hideMessageKeyboard()
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
     * Подписывается на одноразовые runtime-уведомления и показывает их через snackbar.
     */
    private fun collectNotices() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notices.collect(::showNotice)
            }
        }
    }

    /**
     * Отрисовывает connecting-overlay, direct-аудио предупреждение, участников, чат, PTT-состояние и закрытие комнаты.
     */
    private fun renderState(state: RoomUiState) {
        latestState = state
        tvChannelTitle.text = state.roomName.ifBlank { "ROOM" }
        renderConnectingState(state)
        btnSend.visibility = if (state.canSend && !state.isConnecting) View.VISIBLE else View.GONE
        btnSend.alpha = if (btnSend.isEnabled) 1.0f else 0.45f
        val visibleProblem = state.errorMessage ?: state.directAudioIssueMessage
        tvRoomError.text = visibleProblem.orEmpty()
        tvRoomError.visibility = if (visibleProblem.isNullOrBlank()) View.GONE else View.VISIBLE
        showSnackbarIfNeeded(visibleProblem)
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
     * Показывает одноразовое runtime-уведомление, если оно еще не отображалось этим экраном.
     */
    private fun showNotice(notice: RoomRuntimeNotice) {
        if (lastShownNoticeId == notice.id) {
            return
        }
        lastShownNoticeId = notice.id
        showSnackbarIfNeeded(notice.message)
    }

    /**
     * Показывает snackbar с новым текстом ошибки и не дублирует последний показанный текст.
     */
    private fun showSnackbarIfNeeded(message: String?) {
        val cleanMessage = message?.takeIf { it.isNotBlank() } ?: return
        if (lastShownSnackbarMessage == cleanMessage) {
            return
        }
        lastShownSnackbarMessage = cleanMessage
        Snackbar.make(requireView(), cleanMessage, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Показывает ожидание Nearby join, direct-аудио статус и блокирует интерактивное содержимое при подключении.
     */
    private fun renderConnectingState(state: RoomUiState) {
        layoutConnectingOverlay.visibility = if (state.isConnecting) View.VISIBLE else View.GONE
        tvChannelStatus.text = when {
            state.isConnecting -> "ПОДКЛЮЧЕНИЕ…"
            state.directAudioStatusText.isNotBlank() -> state.directAudioStatusText
            else -> getString(R.string.subt_onair)
        }
        tvChannelStatus.setTextColor(channelStatusColor(state))
        etMessage.isEnabled = !state.isConnecting
        btnPushToTalk.isEnabled = !state.isConnecting && state.canTalk
        layoutChatInput.alpha = if (state.isConnecting) 0.45f else 1f
        rvMembers.isEnabled = !state.isConnecting
        rvMessages.isEnabled = !state.isConnecting
    }

    /**
     * Подбирает цвет статуса комнаты так, чтобы ошибка direct-аудио была видна прямо в шапке.
     */
    private fun channelStatusColor(state: RoomUiState): Int {
        return when {
            state.isConnecting -> Color.parseColor("#94A3B8")
            state.directAudioIssueMessage != null -> ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)
            state.directAudioStatusText.isNotBlank() -> ContextCompat.getColor(requireContext(), R.color.second_accent_green)
            else -> ContextCompat.getColor(requireContext(), R.color.second_accent_green)
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
            .setMessage("Для работы Nearby-комнаты нужно включить геолокацию устройства. PSHH MESHRA не читает GPS-координаты.")
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

        tvTransmissionStatus.text = ""
        tvTransmissionStatus.setTextColor(requireContext().getColor(android.R.color.white))
        btnPushToTalk.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

        viewWave1.visibility = View.GONE
        viewWave2.visibility = View.GONE
        viewWave3.visibility = View.GONE

    }

    /**
     * Прячет системную клавиатуру поля сообщения, если оно еще связано с окном.
     */
    private fun hideMessageKeyboard() {
        if (!::etMessage.isInitialized) {
            return
        }
        val currentContext = context ?: return
        val inputMethodManager = currentContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(etMessage.windowToken, 0)
        etMessage.clearFocus()
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
