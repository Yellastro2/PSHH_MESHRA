package com.yellastro.btration

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.runtime.RoomRuntimeNotice
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction
import com.yellastro.btration.ui.lobby.LobbyUiState
import com.yellastro.btration.ui.lobby.LobbyViewModel
import com.yellastro.btration.ui.lobby.RoomItemUi
import com.yellastro.btration.voice.VoiceFrameDuration
import com.yellastro.btration.voice.VoiceTransportPreference
import kotlinx.coroutines.launch

/**
 * Экран лобби с редактором имени, discovery и диалогом transport/voice-профиля новой комнаты.
 */
class LobbyFragment : Fragment() {
    private val viewModel: LobbyViewModel by viewModels {
        (requireActivity().application as BtRationApplication).appContainer.lobbyViewModelFactory()
    }

    private lateinit var tvWelcome: TextView
    private lateinit var etPeerName: EditText
    private lateinit var btnEditName: ImageButton
    private lateinit var btnClearIgnoredPeers: ImageView
    private lateinit var tvLobbyError: TextView
    private lateinit var btnCreateRoom: Button
    private lateinit var viewScanProgress: View
    private lateinit var rvRooms: RecyclerView
    private lateinit var roomsAdapter: RoomsAdapter

    private var openedRoom = false
    private var handledErrorAction: RoomRuntimeErrorAction? = null
    private var lastShownNoticeId = Long.MIN_VALUE
    private var lastShownSnackbarMessage: String? = null
    private var wasEditingName = false
    private var renderedScanCycleId = Long.MIN_VALUE

    /**
     * Создает XML-разметку лобби.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_lobby, container, false)
    }

    /**
     * Настраивает приветствие, список Nearby-комнат и диалог создания комнаты.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvWelcome = view.findViewById(R.id.tv_welcome)
        etPeerName = view.findViewById(R.id.et_peer_name)
        btnEditName = view.findViewById(R.id.btn_edit_name)
        btnClearIgnoredPeers = view.findViewById(R.id.fr_lobby_users)
        tvLobbyError = view.findViewById(R.id.tv_lobby_error)
        btnCreateRoom = view.findViewById(R.id.btn_create_room)
        viewScanProgress = view.findViewById(R.id.view_scan_progress)
        rvRooms = view.findViewById(R.id.rv_rooms)

        roomsAdapter = RoomsAdapter(
            onJoinClick = viewModel::onJoinRoomClicked,
            onIgnoreClick = ::showIgnoreGatewayDialog,
        )
        rvRooms.layoutManager = LinearLayoutManager(requireContext())
        rvRooms.adapter = roomsAdapter

        etPeerName.doAfterTextChanged { value ->
            viewModel.onNameChanged(value?.toString().orEmpty())
        }
        etPeerName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && viewModel.uiState.value.canSaveName) {
                viewModel.onSaveNameClicked()
                true
            } else {
                false
            }
        }
        btnEditName.setOnClickListener {
            if (viewModel.uiState.value.isEditingName) {
                viewModel.onSaveNameClicked()
            } else {
                viewModel.onEditNameClicked()
            }
        }
        btnClearIgnoredPeers.setOnClickListener {
            showClearIgnoredPeersDialog()
        }

        childFragmentManager.setFragmentResultListener(
            CreateRoomDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val newRoomName = bundle.getString(CreateRoomDialogFragment.RESULT_ROOM_NAME).orEmpty()
            val roomTransportMode = RoomTransportMode.fromPrefValue(
                bundle.getString(CreateRoomDialogFragment.RESULT_ROOM_TRANSPORT_MODE),
            )
            val voiceTransportPreference = VoiceTransportPreference.fromPrefValue(
                bundle.getString(CreateRoomDialogFragment.RESULT_VOICE_TRANSPORT_PREF),
            )
            val voiceFrameDuration = VoiceFrameDuration.fromPrefValue(
                value = bundle.getString(CreateRoomDialogFragment.RESULT_VOICE_FRAME_DURATION),
                default = viewModel.voiceFrameDurationForDialog(roomTransportMode),
            )
            if (newRoomName.isBlank()) {
                return@setFragmentResultListener
            }
            viewModel.onCreateRoomClicked(
                name = newRoomName,
                roomTransportMode = roomTransportMode,
                voiceTransportPreference = voiceTransportPreference,
                voiceFrameDuration = voiceFrameDuration,
            )
        }

        btnCreateRoom.setOnClickListener {
            val dialog = CreateRoomDialogFragment.newInstance(
                initialRoomName = viewModel.lastRoomNameForDialog(),
                initialRoomTransportMode = viewModel.roomTransportModeForDialog(),
                initialVoiceTransportPreference = viewModel.voiceTransportPreferenceForDialog(),
                initialNearbyStarFrameDuration = viewModel.voiceFrameDurationForDialog(RoomTransportMode.NEARBY_STAR),
                initialMeshraFrameDuration = viewModel.voiceFrameDurationForDialog(RoomTransportMode.MESHRA),
            )
            dialog.show(childFragmentManager, "CreateRoomDialog")
        }

        collectUiState()
        collectNotices()
    }

    /**
     * Перезапускает поиск комнат при каждом возвращении лобби в resumed-состояние.
     */
    override fun onResume() {
        super.onResume()
        viewModel.onLobbyResumed()
    }

    /**
     * Останавливает периодические discovery-циклы, когда лобби теряет resumed-состояние.
     */
    override fun onPause() {
        stopScanProgress()
        viewModel.onLobbyPaused()
        super.onPause()
    }

    /**
     * Подписывается на UI-состояние лобби.
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
     * Отрисовывает приветствие, ошибку, список комнат и переход в комнату.
     */
    private fun renderState(state: LobbyUiState) {
        val selfName = state.selfName.ifBlank { "Гость" }
        tvWelcome.text = "Привет, $selfName!"
        renderNameEditor(state)
        renderScanProgress(state)
        tvLobbyError.text = state.errorMessage.orEmpty()
        tvLobbyError.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        showSnackbarIfNeeded(state.errorMessage)
        showErrorActionIfNeeded(state.errorAction)
        roomsAdapter.submitItems(state.availableRooms)
        if (!state.isInRoom) {
            openedRoom = false
        } else if (!openedRoom) {
            openedRoom = true
            (activity as? MainActivity)?.navigateTo(RoomFragment.newInstance())
        }
    }

    /**
     * Переключает приветствие и поле имени, обновляет иконку и управляет клавиатурой только при смене режима.
     */
    private fun renderNameEditor(state: LobbyUiState) {
        tvWelcome.visibility = if (state.isEditingName) View.GONE else View.VISIBLE
        etPeerName.visibility = if (state.isEditingName) View.VISIBLE else View.GONE
        btnEditName.setImageResource(if (state.isEditingName) R.drawable.ic_check else R.drawable.ic_edit)
        btnEditName.contentDescription = if (state.isEditingName) "Сохранить имя" else "Редактировать имя"
        btnEditName.isEnabled = !state.isEditingName || state.canSaveName
        btnEditName.alpha = if (btnEditName.isEnabled) 1f else 0.45f

        if (etPeerName.text.toString() != state.nameInput) {
            etPeerName.setText(state.nameInput)
            etPeerName.setSelection(etPeerName.text.length)
        }
        if (state.isEditingName != wasEditingName) {
            wasEditingName = state.isEditingName
            if (state.isEditingName) {
                etPeerName.requestFocus()
                etPeerName.post { showNameKeyboard() }
            } else {
                etPeerName.clearFocus()
                hideNameKeyboard()
            }
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
     * Показывает системную клавиатуру для поля имени.
     */
    private fun showNameKeyboard() {
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(etPeerName, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Скрывает системную клавиатуру после сохранения имени.
     */
    private fun hideNameKeyboard() {
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(etPeerName.windowToken, 0)
    }

    /**
     * Сбрасывает линию при новом discovery-цикле и линейно заполняет ее за фактическую длительность цикла.
     */
    private fun renderScanProgress(state: LobbyUiState) {
        if (!state.isSearching) {
            stopScanProgress()
            return
        }
        if (renderedScanCycleId == state.scanCycleId) {
            return
        }
        renderedScanCycleId = state.scanCycleId
        viewScanProgress.animate().cancel()
        viewScanProgress.scaleX = 0f
        viewScanProgress.animate()
            .scaleX(1f)
            .setDuration(state.scanCycleDurationMillis)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    /**
     * Останавливает анимацию сканирования и возвращает линию к левому краю.
     */
    private fun stopScanProgress() {
        if (!::viewScanProgress.isInitialized) {
            return
        }
        viewScanProgress.animate().cancel()
        viewScanProgress.scaleX = 0f
        renderedScanCycleId = Long.MIN_VALUE
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
     * Предлагает включить системную геолокацию, без которой Nearby discovery не стартует.
     */
    private fun showLocationSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Геолокация выключена")
            .setMessage("Для поиска Nearby-комнат нужно включить геолокацию устройства. PSHH MESHRA не читает GPS-координаты.")
            .setPositiveButton("Включить") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    /**
     * Показывает подтверждение игнора прямого gateway найденной комнаты.
     */
    private fun showIgnoreGatewayDialog(room: RoomItemUi) {
        AlertDialog.Builder(requireContext())
            .setTitle("Игнорить gateway?")
            .setMessage("Реклама от ${room.gatewayName} больше не будет использоваться напрямую. Эта же mesh-комната может остаться видимой через другие узлы.")
            .setPositiveButton("Игнорить") { _, _ ->
                viewModel.onIgnoreRoomClicked(room)
                showSnackbarIfNeeded("Gateway ${room.gatewayName} добавлен в ignore-list")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Показывает подтверждение очистки локального ignore-list Nearby peer-ов/gateway-ев.
     */
    private fun showClearIgnoredPeersDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Очистить игнор лист?")
            .setMessage("После очистки скрытые Nearby gateway-и снова появятся в лобби при следующем discovery.")
            .setPositiveButton("Да") { _, _ ->
                viewModel.onClearIgnoredPeersConfirmed()
                showSnackbarIfNeeded("Ignore-list очищен")
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    /**
     * Адаптер списка комнат в лобби.
     */
    private class RoomsAdapter(
        private val onJoinClick: (RoomItemUi) -> Unit,
        private val onIgnoreClick: (RoomItemUi) -> Unit,
    ) : RecyclerView.Adapter<RoomsAdapter.ViewHolder>() {
        private val items = mutableListOf<RoomItemUi>()

        /**
         * ViewHolder карточки комнаты.
         */
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvRoomName: TextView = view.findViewById(R.id.tv_room_name)
            val tvHostName: TextView = view.findViewById(R.id.tv_host_name)
            val tvMemberCount: TextView = view.findViewById(R.id.tv_member_count)
            val tvLostSignal: TextView = view.findViewById(R.id.tv_lost_signal)
            val btnJoin: Button = view.findViewById(R.id.btn_join)
        }

        /**
         * Заменяет список комнат и перерисовывает RecyclerView.
         */
        fun submitItems(newItems: List<RoomItemUi>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        /**
         * Создает карточку комнаты из XML-разметки.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false)
            return ViewHolder(view)
        }

        /**
         * Заполняет карточку комнаты и состояние кнопки входа.
         */
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val room = items[position]
            holder.tvRoomName.text = room.roomName
            holder.tvHostName.text = "Host: ${room.hostName}"
            holder.tvMemberCount.text = room.memberCountText ?: "участники рядом"
            holder.tvLostSignal.visibility = View.GONE
            holder.btnJoin.isEnabled = true
            holder.btnJoin.text = "Войти"
            holder.btnJoin.alpha = 1.0f

            holder.btnJoin.setOnClickListener {
                onJoinClick(room)
            }
            holder.itemView.setOnLongClickListener {
                onIgnoreClick(room)
                true
            }
        }

        /**
         * Возвращает количество найденных комнат.
         */
        override fun getItemCount(): Int = items.size
    }
}
