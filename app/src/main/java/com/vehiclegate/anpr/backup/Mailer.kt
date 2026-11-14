package com.vehiclegate.anpr.backup

import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Sends mail through Gmail (smtp.gmail.com, STARTTLS) with an app password. */
object Mailer {
    fun send(gmail: String, appPassword: String, to: List<String>, subject: String, body: String, files: List<File>) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
        }
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(gmail, appPassword)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(gmail, "Vehicle Entry Monitor"))
            setRecipients(Message.RecipientType.TO, to.joinToString(",") { it })
            setSubject(subject, "UTF-8")
        }
        val parts = MimeMultipart()
        parts.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
        for (f in files) parts.addBodyPart(MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(f))
            fileName = f.name
        })
        msg.setContent(parts)
        Transport.send(msg)
    }
}
