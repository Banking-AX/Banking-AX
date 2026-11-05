package com.bankingax.task.b01;

interface B01SmsSender {
    SendReceipt send(String phone, String text);

    record SendReceipt(String provider, String acceptanceStatus) {
        static SendReceipt accepted(String provider) {
            return new SendReceipt(provider, "ACCEPTED");
        }
    }
}
