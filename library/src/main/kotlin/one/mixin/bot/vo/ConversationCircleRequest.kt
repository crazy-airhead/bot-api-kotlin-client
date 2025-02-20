package one.mixin.bot.vo

import com.google.gson.annotations.SerializedName

data class ConversationCircleRequest(
    @SerializedName("circle_id")
    val circleId: String,
    @SerializedName("action")
    val action: String
)
