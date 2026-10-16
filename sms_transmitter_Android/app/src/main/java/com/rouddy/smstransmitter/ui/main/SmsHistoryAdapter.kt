package com.rouddy.smstransmitter.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.rouddy.smstransmitter.R
import com.rouddy.smstransmitter.data.SmsHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsHistoryAdapter(
    private val dateFormatter: SimpleDateFormat
) : RecyclerView.Adapter<SmsHistoryAdapter.ViewHolder>() {

    private var items: List<SmsHistoryItem> = emptyList()

    fun submitList(newItems: List<SmsHistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sms_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textTimestamp: TextView = view.findViewById(R.id.text_timestamp)
        private val textSender: TextView = view.findViewById(R.id.text_sender)
        private val textReceiver: TextView = view.findViewById(R.id.text_receiver)
        private val textMessage: TextView = view.findViewById(R.id.text_message)
        private val textStatus: TextView = view.findViewById(R.id.text_status)

        fun bind(item: SmsHistoryItem) {
            val context = itemView.context
            val formattedTime = dateFormatter.format(Date(item.createdAtMillis))
            textTimestamp.text = context.getString(R.string.history_time, formattedTime)
            textSender.text = context.getString(R.string.history_sender, item.senderPhoneNumber ?: "-")
            textReceiver.text = context.getString(R.string.history_receiver, item.phoneNumber)
            textMessage.text = context.getString(R.string.history_message, item.message)

            val smsStatus = when {
                item.smsPending -> context.getString(R.string.sms_pending)
                item.smsSuccess -> context.getString(R.string.sms_success)
                else -> context.getString(R.string.sms_failure)
            }

            textStatus.text = if (!item.smsError.isNullOrBlank()) {
                context.getString(R.string.history_status_with_error, smsStatus, item.smsError)
            } else {
                context.getString(R.string.history_status, smsStatus)
            }

            val statusColor = when {
                item.smsPending -> android.R.color.holo_orange_dark
                item.smsSuccess -> android.R.color.holo_green_dark
                else -> android.R.color.holo_red_dark
            }
            textStatus.setTextColor(ContextCompat.getColor(context, statusColor))
        }
    }
}
