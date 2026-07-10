package com.yellastro.btration

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction
import com.yellastro.btration.ui.lobby.LobbyUiState
import com.yellastro.btration.ui.lobby.LobbyViewModel
import com.yellastro.btration.ui.lobby.RoomItemUi
import kotlinx.coroutines.launch

/**
 * Экран лобби со списком Nearby-комнат и созданием локальной комнаты.
 */
class LobbyFragment : Fragment() {
    private val viewModel: LobbyViewModel by viewModels {
        (requireActivity().application as BtRationApplication).appContainer.lobbyViewModelFactory()
    }

    private lateinit var tvWelcome: TextView
    private lateinit var tvLobbyError: TextView
    private lateinit var btnCreateRoom: Button
    private lateinit var rvRooms: RecyclerView
    private lateinit var roomsAdapter: RoomsAdapter

    private var openedRoom = false
    private var handledErrorAction: RoomRuntimeErrorAction? = null

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
        tvLobbyError = view.findViewById(R.id.tv_lobby_error)
        btnCreateRoom = view.findViewById(R.id.btn_create_room)
        rvRooms = view.findViewById(R.id.rv_rooms)

        roomsAdapter = RoomsAdapter { room ->
            viewModel.onJoinRoomClicked(room.roomId)
        }
        rvRooms.layoutManager = LinearLayoutManager(requireContext())
        rvRooms.adapter = roomsAdapter

        childFragmentManager.setFragmentResultListener(
            CreateRoomDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val newRoomName = bundle.getString(CreateRoomDialogFragment.RESULT_ROOM_NAME).orEmpty()
            if (newRoomName.isBlank()) {
                return@setFragmentResultListener
            }
            viewModel.onCreateRoomClicked(newRoomName)
        }

        btnCreateRoom.setOnClickListener {
            val dialog = CreateRoomDialogFragment()
            dialog.show(childFragmentManager, "CreateRoomDialog")
        }

        collectUiState()
    }

    /**
     * Запускает поиск комнат, когда лобби становится видимым.
     */
    override fun onStart() {
        super.onStart()
        viewModel.onStartSearchClicked()
    }

    /**
     * Останавливает discovery при уходе с лобби.
     */
    override fun onStop() {
        super.onStop()
        viewModel.onStopSearchClicked()
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
     * Отрисовывает приветствие, ошибку, список комнат и переход в комнату.
     */
    private fun renderState(state: LobbyUiState) {
        val selfName = state.selfName.ifBlank { "Гость" }
        tvWelcome.text = "Привет, $selfName!"
        tvLobbyError.text = state.errorMessage.orEmpty()
        tvLobbyError.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
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
            .setMessage("Для поиска Nearby-комнат нужно включить геолокацию устройства. BtRation не читает GPS-координаты.")
            .setPositiveButton("Включить") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    /**
     * Адаптер списка комнат в лобби.
     */
    private class RoomsAdapter(
        private val onJoinClick: (RoomItemUi) -> Unit
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
        }

        /**
         * Возвращает количество найденных комнат.
         */
        override fun getItemCount(): Int = items.size
    }
}
