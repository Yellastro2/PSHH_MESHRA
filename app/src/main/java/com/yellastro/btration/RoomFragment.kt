package com.yellastro.btration

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Моковый экран комнаты со списком участников, чатом и визуальным PTT-контролом.
 */
class RoomFragment : Fragment() {

    private lateinit var tvChannelTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var rvMessages: RecyclerView
    private lateinit var rvMembers: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton

    private lateinit var btnPushToTalk: View
    private lateinit var tvTransmissionStatus: TextView
    private lateinit var viewWave1: View
    private lateinit var viewWave2: View
    private lateinit var viewWave3: View

    private var roomId: String = ""
    private var roomName: String = ""
    private var hostName: String = ""
    private var selfName: String = "Гость"

    private val messages = mutableListOf<Message>()
    private val members = mutableListOf<Member>()

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var memberAdapter: MemberAdapter

    private var isTransmitting = false

    /**
     * Фабрика RoomFragment с аргументами моковой комнаты.
     */
    companion object {
        /**
         * Создает экран комнаты с идентификатором, названием и именем хоста.
         */
        fun newInstance(roomId: String, roomName: String, hostName: String): RoomFragment {
            val fragment = RoomFragment()
            val args = Bundle().apply {
                putString("room_id", roomId)
                putString("room_name", roomName)
                putString("host_name", hostName)
            }
            fragment.arguments = args
            return fragment
        }
    }

    /**
     * Читает аргументы комнаты до создания view.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            roomId = it.getString("room_id", "")
            roomName = it.getString("room_name", "")
            hostName = it.getString("host_name", "")
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
     * Настраивает моковые списки, чат, кнопки и PTT-визуализацию.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPrefs = requireActivity().getSharedPreferences("walkie_talkie_prefs", Context.MODE_PRIVATE)
        selfName = sharedPrefs.getString("self_name", "Гость") ?: "Гость"

        tvChannelTitle = view.findViewById(R.id.tv_channel_title)
        btnBack = view.findViewById(R.id.btn_back)
        rvMessages = view.findViewById(R.id.rv_messages)
        rvMembers = view.findViewById(R.id.rv_members)
        etMessage = view.findViewById(R.id.et_message)
        btnSend = view.findViewById(R.id.btn_send)

        btnPushToTalk = view.findViewById(R.id.btn_push_to_talk)
        tvTransmissionStatus = view.findViewById(R.id.tv_transmission_status)
        viewWave1 = view.findViewById(R.id.view_wave_1)
        viewWave2 = view.findViewById(R.id.view_wave_2)
        viewWave3 = view.findViewById(R.id.view_wave_3)

        tvChannelTitle.text = roomName

        setupMockData()

        messageAdapter = MessageAdapter(messages)
        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = messageAdapter

        memberAdapter = MemberAdapter(members)
        rvMembers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvMembers.adapter = memberAdapter

        btnBack.setOnClickListener {
            (activity as? MainActivity)?.popBackStack()
        }

        btnSend.setOnClickListener {
            sendMessage()
        }

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
    }

    /**
     * Наполняет экран статичными моковыми сообщениями и участниками.
     */
    private fun setupMockData() {
        messages.clear()
        messages.add(Message("sys_1", "Вы подключились к каналу $roomName", "SYSTEM", "", isSystem = true))
        messages.add(Message("m1", "Привет всем! Слышно меня?", hostName, "13:02"))
        messages.add(Message("m2", "Да, прием отличный, чистый звук!", "Dmitry", "13:04"))

        members.clear()
        members.add(
            Member(
                "self",
                selfName,
                isTalking = false,
                isMe = true,
                role = if (selfName == hostName) "HOST" else "USER",
            ),
        )
        if (hostName != selfName) {
            members.add(Member("host", hostName, isTalking = false, role = "HOST"))
        }
        members.add(Member("m_dmitry", "Dmitry", isTalking = false))
        members.add(Member("m_anna", "Anna", isMuted = true))
    }

    /**
     * Добавляет локальное моковое сообщение из поля ввода.
     */
    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTime = sdf.format(Date())

        val newMsg = Message(
            id = "msg_${System.currentTimeMillis()}",
            text = text,
            senderName = selfName,
            timestamp = currentTime,
            isMe = true,
        )

        messages.add(newMsg)
        messageAdapter.notifyItemInserted(messages.size - 1)
        rvMessages.scrollToPosition(messages.size - 1)

        etMessage.text.clear()
    }

    /**
     * Включает визуальное состояние передачи и отмечает себя говорящим.
     */
    private fun startTransmission() {
        if (isTransmitting) return
        isTransmitting = true

        tvTransmissionStatus.text = "ПЕРЕДАЧА..."
        tvTransmissionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light))
        btnPushToTalk.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()

        viewWave1.visibility = View.VISIBLE
        viewWave2.visibility = View.VISIBLE
        viewWave3.visibility = View.VISIBLE

        updateSelfTalking(true)
    }

    /**
     * Выключает визуальное состояние передачи и снимает talking-статус.
     */
    private fun stopTransmission() {
        if (!isTransmitting) return
        isTransmitting = false

        tvTransmissionStatus.text = "УДЕРЖИВАЙТЕ ДЛЯ СВЯЗИ"
        tvTransmissionStatus.setTextColor(requireContext().getColor(android.R.color.white))
        btnPushToTalk.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

        viewWave1.visibility = View.GONE
        viewWave2.visibility = View.GONE
        viewWave3.visibility = View.GONE

        updateSelfTalking(false)
    }

    /**
     * Обновляет talking-состояние локального участника в моковом списке.
     */
    private fun updateSelfTalking(talking: Boolean) {
        val selfIndex = members.indexOfFirst { it.isMe }
        if (selfIndex != -1) {
            val updated = members[selfIndex].copy(isTalking = talking)
            members[selfIndex] = updated
            memberAdapter.notifyItemChanged(selfIndex)
        }
    }

    /**
     * Адаптер мокового списка сообщений с разными layout для своих, чужих и системных сообщений.
     */
    private class MessageAdapter(private val items: List<Message>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_SYSTEM = 0
        private val TYPE_ME = 1
        private val TYPE_OTHER = 2

        /**
         * Выбирает тип карточки сообщения.
         */
        override fun getItemViewType(position: Int): Int {
            val item = items[position]
            return when {
                item.isSystem -> TYPE_SYSTEM
                item.isMe -> TYPE_ME
                else -> TYPE_OTHER
            }
        }

        /**
         * Создает ViewHolder для выбранного типа сообщения.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SYSTEM -> {
                    val view = inflater.inflate(R.layout.item_system_message, parent, false)
                    SystemViewHolder(view)
                }
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
            if (holder is SystemViewHolder) {
                holder.tvText.text = item.text
            } else if (holder is MessageViewHolder) {
                holder.tvSender.text = item.senderName
                holder.tvTime.text = item.timestamp
                holder.tvText.text = item.text
            }
        }

        /**
         * Возвращает количество сообщений в моковом списке.
         */
        override fun getItemCount(): Int = items.size

        /**
         * ViewHolder системного сообщения.
         */
        class SystemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvText: TextView = view.findViewById(R.id.tv_system_text)
        }

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
     * Адаптер горизонтального мокового списка участников.
     */
    private class MemberAdapter(private val items: List<Member>) :
        RecyclerView.Adapter<MemberAdapter.ViewHolder>() {

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

            val subtitle = when {
                member.isMe && member.role == "HOST" -> "Вы • Хост"
                member.isMe -> "Вы"
                member.role == "HOST" -> "Хост"
                else -> "Участник"
            }
            holder.tvStatus.text = subtitle

            if (member.isTalking) {
                holder.viewIndicator.setBackgroundResource(R.drawable.bg_dot_talking)
                holder.viewIndicator.visibility = View.VISIBLE
            } else if (member.isMuted) {
                holder.viewIndicator.visibility = View.GONE
            } else {
                holder.viewIndicator.setBackgroundResource(R.drawable.bg_dot_idle)
                holder.viewIndicator.visibility = View.VISIBLE
            }

            holder.ivMuted.visibility = if (member.isMuted) View.VISIBLE else View.GONE
        }

        /**
         * Возвращает количество участников в моковом списке.
         */
        override fun getItemCount(): Int = items.size
    }
}
