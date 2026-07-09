package com.yellastro.btration

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Моковый экран лобби со списком найденных комнат и созданием локальной комнаты.
 */
class LobbyFragment : Fragment() {

    private lateinit var tvWelcome: TextView
    private lateinit var btnCreateRoom: Button
    private lateinit var rvRooms: RecyclerView
    private lateinit var roomsAdapter: RoomsAdapter

    private var selfName: String = "Гость"
    private val mockRooms = mutableListOf<Room>()

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
     * Настраивает приветствие, моковые комнаты, список и диалог создания комнаты.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPrefs = requireActivity().getSharedPreferences("walkie_talkie_prefs", Context.MODE_PRIVATE)
        selfName = sharedPrefs.getString("self_name", "Гость") ?: "Гость"

        tvWelcome = view.findViewById(R.id.tv_welcome)
        btnCreateRoom = view.findViewById(R.id.btn_create_room)
        rvRooms = view.findViewById(R.id.rv_rooms)

        tvWelcome.text = "Привет, $selfName!"

        setupMockRooms()

        roomsAdapter = RoomsAdapter(mockRooms) { room ->
            joinRoom(room)
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
            createMockRoom(newRoomName)
        }

        btnCreateRoom.setOnClickListener {
            val dialog = CreateRoomDialogFragment()
            dialog.show(childFragmentManager, "CreateRoomDialog")
        }
    }

    /**
     * Наполняет лобби статичными моковыми комнатами до подключения runtime.
     */
    private fun setupMockRooms() {
        mockRooms.clear()
        mockRooms.add(Room("1", "CH-19 ALPHA", "Max", 4, "4"))
        mockRooms.add(Room("2", "SIERRA 7", "Dmitry", 2, "2"))
        mockRooms.add(Room("3", "STALKER OUTPOST", "Alex", 1, "1", isLost = true))
        mockRooms.add(Room("4", "METRO FREQ", "Artem", 6, "6"))
    }

    /**
     * Создает моковую комнату, добавляет ее наверх списка и открывает экран комнаты.
     */
    private fun createMockRoom(newRoomName: String) {
        val newRoom = Room(
            id = "room_${System.currentTimeMillis()}",
            name = newRoomName,
            hostName = selfName,
            memberCount = 1,
            memberCountText = "1",
        )
        mockRooms.add(0, newRoom)
        roomsAdapter.notifyItemInserted(0)
        rvRooms.scrollToPosition(0)
        joinRoom(newRoom)
    }

    /**
     * Открывает моковый экран комнаты для выбранной записи.
     */
    private fun joinRoom(room: Room) {
        val fragment = RoomFragment.newInstance(room.id, room.name, room.hostName)
        (activity as? MainActivity)?.navigateTo(fragment)
    }

    /**
     * Адаптер списка комнат в лобби.
     */
    private class RoomsAdapter(
        private val items: List<Room>,
        private val onJoinClick: (Room) -> Unit
    ) : RecyclerView.Adapter<RoomsAdapter.ViewHolder>() {

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
            holder.tvRoomName.text = room.name
            holder.tvHostName.text = "Host: ${room.hostName}"
            holder.tvMemberCount.text = "${room.memberCountText} участников"

            if (room.isLost) {
                holder.tvLostSignal.visibility = View.VISIBLE
                holder.btnJoin.isEnabled = false
                holder.btnJoin.text = "Lost"
                holder.btnJoin.alpha = 0.5f
            } else {
                holder.tvLostSignal.visibility = View.GONE
                holder.btnJoin.isEnabled = true
                holder.btnJoin.text = "Войти"
                holder.btnJoin.alpha = 1.0f
            }

            holder.btnJoin.setOnClickListener {
                onJoinClick(room)
            }
        }

        /**
         * Возвращает количество комнат в текущем моковом списке.
         */
        override fun getItemCount(): Int = items.size
    }
}
