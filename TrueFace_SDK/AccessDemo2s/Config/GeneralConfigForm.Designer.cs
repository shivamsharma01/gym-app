namespace AccessDemo2s
{
    partial class GeneralConfigForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.btn_Set = new System.Windows.Forms.Button();
            this.btn_Get = new System.Windows.Forms.Button();
            this.chb_Break = new System.Windows.Forms.CheckBox();
            this.label15 = new System.Windows.Forms.Label();
            this.chb_Sensor = new System.Windows.Forms.CheckBox();
            this.label14 = new System.Windows.Forms.Label();
            this.chb_Duress = new System.Windows.Forms.CheckBox();
            this.label = new System.Windows.Forms.Label();
            this.chb_Unclose = new System.Windows.Forms.CheckBox();
            this.label12 = new System.Windows.Forms.Label();
            this.chb_Repear = new System.Windows.Forms.CheckBox();
            this.label11 = new System.Windows.Forms.Label();
            this.txt_HolidayTime = new System.Windows.Forms.TextBox();
            this.label10 = new System.Windows.Forms.Label();
            this.txt_CloseTimeout = new System.Windows.Forms.TextBox();
            this.label9 = new System.Windows.Forms.Label();
            this.txt_UnlockHold = new System.Windows.Forms.TextBox();
            this.label8 = new System.Windows.Forms.Label();
            this.cmb_AccessState = new System.Windows.Forms.ComboBox();
            this.label7 = new System.Windows.Forms.Label();
            this.cmb_OpenMethod = new System.Windows.Forms.ComboBox();
            this.label6 = new System.Windows.Forms.Label();
            this.cmb_Channel = new System.Windows.Forms.ComboBox();
            this.label1 = new System.Windows.Forms.Label();
            this.txt_CloseTime = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.txt_OpenTime = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.txt_RemoteTime = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.chb_RemoteEnable = new System.Windows.Forms.CheckBox();
            this.btn_OpenTime = new System.Windows.Forms.Button();
            this.label5 = new System.Windows.Forms.Label();
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.btn_Manager = new System.Windows.Forms.Button();
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.btn_Config = new System.Windows.Forms.Button();
            this.chb_MaliciousSwip = new System.Windows.Forms.CheckBox();
            this.label13 = new System.Windows.Forms.Label();
            this.chb_CustomPwd = new System.Windows.Forms.CheckBox();
            this.label17 = new System.Windows.Forms.Label();
            this.groupBox1.SuspendLayout();
            this.groupBox2.SuspendLayout();
            this.SuspendLayout();
            // 
            // btn_Set
            // 
            this.btn_Set.Font = new System.Drawing.Font("", 12F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Set.Location = new System.Drawing.Point(424, 456);
            this.btn_Set.Name = "btn_Set";
            this.btn_Set.Size = new System.Drawing.Size(140, 30);
            this.btn_Set.TabIndex = 52;
            this.btn_Set.Text = "Set()";
            this.btn_Set.UseVisualStyleBackColor = true;
            this.btn_Set.Click += new System.EventHandler(this.btn_Set_Click);
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("", 12F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(170, 456);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(140, 30);
            this.btn_Get.TabIndex = 33;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // chb_Break
            // 
            this.chb_Break.AutoSize = true;
            this.chb_Break.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Break.Location = new System.Drawing.Point(655, 217);
            this.chb_Break.Name = "chb_Break";
            this.chb_Break.Size = new System.Drawing.Size(15, 14);
            this.chb_Break.TabIndex = 51;
            this.chb_Break.UseVisualStyleBackColor = true;
            // 
            // label15
            // 
            this.label15.AutoSize = true;
            this.label15.Location = new System.Drawing.Point(494, 217);
            this.label15.Name = "label15";
            this.label15.Size = new System.Drawing.Size(154, 14);
            this.label15.TabIndex = 50;
            this.label15.Text = "BreakAlarm():";
            // 
            // chb_Sensor
            // 
            this.chb_Sensor.AutoSize = true;
            this.chb_Sensor.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Sensor.Location = new System.Drawing.Point(654, 137);
            this.chb_Sensor.Name = "chb_Sensor";
            this.chb_Sensor.Size = new System.Drawing.Size(15, 14);
            this.chb_Sensor.TabIndex = 49;
            this.chb_Sensor.UseVisualStyleBackColor = true;
            // 
            // label14
            // 
            this.label14.AutoSize = true;
            this.label14.Location = new System.Drawing.Point(550, 137);
            this.label14.Name = "label14";
            this.label14.Size = new System.Drawing.Size(98, 14);
            this.label14.TabIndex = 48;
            this.label14.Text = "Sensor():";
            // 
            // chb_Duress
            // 
            this.chb_Duress.AutoSize = true;
            this.chb_Duress.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Duress.Location = new System.Drawing.Point(654, 97);
            this.chb_Duress.Name = "chb_Duress";
            this.chb_Duress.Size = new System.Drawing.Size(15, 14);
            this.chb_Duress.TabIndex = 47;
            this.chb_Duress.UseVisualStyleBackColor = true;
            // 
            // label
            // 
            this.label.AutoSize = true;
            this.label.Location = new System.Drawing.Point(487, 97);
            this.label.Name = "label";
            this.label.Size = new System.Drawing.Size(161, 14);
            this.label.TabIndex = 46;
            this.label.Text = "DuressAlarm():";
            // 
            // chb_Unclose
            // 
            this.chb_Unclose.AutoSize = true;
            this.chb_Unclose.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Unclose.Location = new System.Drawing.Point(654, 57);
            this.chb_Unclose.Name = "chb_Unclose";
            this.chb_Unclose.Size = new System.Drawing.Size(15, 14);
            this.chb_Unclose.TabIndex = 45;
            this.chb_Unclose.UseVisualStyleBackColor = true;
            // 
            // label12
            // 
            this.label12.AutoSize = true;
            this.label12.Location = new System.Drawing.Point(431, 57);
            this.label12.Name = "label12";
            this.label12.Size = new System.Drawing.Size(217, 14);
            this.label12.TabIndex = 44;
            this.label12.Text = "DoorNotCloseAlarm():";
            // 
            // chb_Repear
            // 
            this.chb_Repear.AutoSize = true;
            this.chb_Repear.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Repear.Location = new System.Drawing.Point(654, 17);
            this.chb_Repear.Name = "chb_Repear";
            this.chb_Repear.Size = new System.Drawing.Size(15, 14);
            this.chb_Repear.TabIndex = 43;
            this.chb_Repear.UseVisualStyleBackColor = true;
            // 
            // label11
            // 
            this.label11.AutoSize = true;
            this.label11.Location = new System.Drawing.Point(424, 17);
            this.label11.Name = "label11";
            this.label11.Size = new System.Drawing.Size(224, 14);
            this.label11.TabIndex = 42;
            this.label11.Text = "RepearEventAlarm():";
            // 
            // txt_HolidayTime
            // 
            this.txt_HolidayTime.Location = new System.Drawing.Point(257, 230);
            this.txt_HolidayTime.Name = "txt_HolidayTime";
            this.txt_HolidayTime.Size = new System.Drawing.Size(119, 23);
            this.txt_HolidayTime.TabIndex = 41;
            // 
            // label10
            // 
            this.label10.AutoSize = true;
            this.label10.Location = new System.Drawing.Point(13, 233);
            this.label10.Name = "label10";
            this.label10.Size = new System.Drawing.Size(238, 14);
            this.label10.TabIndex = 40;
            this.label10.Text = "HolidayTimeRecNo():";
            // 
            // txt_CloseTimeout
            // 
            this.txt_CloseTimeout.Location = new System.Drawing.Point(257, 176);
            this.txt_CloseTimeout.Name = "txt_CloseTimeout";
            this.txt_CloseTimeout.Size = new System.Drawing.Size(119, 23);
            this.txt_CloseTimeout.TabIndex = 39;
            // 
            // label9
            // 
            this.label9.AutoSize = true;
            this.label9.Location = new System.Drawing.Point(55, 179);
            this.label9.Name = "label9";
            this.label9.Size = new System.Drawing.Size(196, 14);
            this.label9.TabIndex = 38;
            this.label9.Text = "CloseTimeout():";
            // 
            // txt_UnlockHold
            // 
            this.txt_UnlockHold.Location = new System.Drawing.Point(257, 122);
            this.txt_UnlockHold.Name = "txt_UnlockHold";
            this.txt_UnlockHold.Size = new System.Drawing.Size(119, 23);
            this.txt_UnlockHold.TabIndex = 37;
            // 
            // label8
            // 
            this.label8.AutoSize = true;
            this.label8.Location = new System.Drawing.Point(69, 125);
            this.label8.Name = "label8";
            this.label8.Size = new System.Drawing.Size(182, 14);
            this.label8.TabIndex = 35;
            this.label8.Text = "UnlockHold():";
            // 
            // cmb_AccessState
            // 
            this.cmb_AccessState.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_AccessState.DropDownWidth = 130;
            this.cmb_AccessState.FormattingEnabled = true;
            this.cmb_AccessState.Items.AddRange(new object[] {
            "Normal()",
            "CloseAlways()",
            "OpenAlways()"});
            this.cmb_AccessState.Location = new System.Drawing.Point(654, 334);
            this.cmb_AccessState.Name = "cmb_AccessState";
            this.cmb_AccessState.Size = new System.Drawing.Size(98, 22);
            this.cmb_AccessState.TabIndex = 36;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(487, 337);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(161, 14);
            this.label7.TabIndex = 34;
            this.label7.Text = "AccessState():";
            // 
            // cmb_OpenMethod
            // 
            this.cmb_OpenMethod.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_OpenMethod.DropDownWidth = 600;
            this.cmb_OpenMethod.FormattingEnabled = true;
            this.cmb_OpenMethod.Items.AddRange(new object[] {
            "UNKNOWN()",
            "PWD_ONLY()",
            "CARD()",
            "PWD_OR_CARD()",
            "CARD_FIRST()",
            "PWD_FIRST()",
            "SECTION()",
            "FINGERPRINTONLY(ZW)",
            "PWD_OR_CARD_OR_FINGERPRINT(ZW)",
            "PWD_AND_CARD_AND_FINGERPINT(++ZW)",
            "PWD_AND_FINGERPRINT(+ZW)",
            "CARD_AND_FINGERPRINT(+ZW)",
            "MULTI_PERSON()",
            "FACEIDCARD()",
            "FACEIDCARD_AND_IDCARD(SFZ+ )",
            "FACEIDCARD_OR_CARD_OR_FINGER(ZW)",
            "FACEIPCARDANDIDCARD_OR_CARD_OR_FINGER((SFZ+)ZW)",
            "USERID_AND_PWD(UserID+)",
            "FACE_ONLY()",
            "FACE_AND_PWD(+)",
            "FINGERPRINT_AND_PWD(ZW+)",
            "FINGERPRINT_AND_FACE(ZW+)",
            "CARD_AND_FACE(+)",
            "FACE_OR_PWD()",
            "FINGERPRINT_OR_PWD(ZW)",
            "FINGERPRINT_OR_FACE(ZW)",
            "CARD_OR_FACE()",
            "CARD_OR_FINGERPRINT(ZW)",
            "FINGERPRINT_AND_FACE_AND_PWD(ZW++)",
            "CARD_AND_FACE_AND_PWD(++)",
            "CARD_AND_FINGERPRINT_AND_PWD(+ZW+)",
            "CARD_AND_PWD_AND_FACE(+ZW+)",
            "FINGERPRINT_OR_FACE_OR_PWD(ZW)",
            "CARD_OR_FACE_OR_PWD()",
            "CARD_OR_FINGERPRINT_OR_FACE(ZW)",
            "CARD_AND_FINGERPRINT_AND_FACE_AND_PWD(+ZW++ )",
            "CARD_OR_FINGERPRINT_OR_FACE_OR_PWD(ZW)",
            "FACEIPCARDANDIDCARD_OR_CARD_OR_FACE(SFZ+    )",
            "FACEIDCARD_OR_CARD_OR_FACE(  ()  )",
            "CARDANDPWD_OR_FINGERPRINTANDPWD((+）（ZW+）2)",
            "PHOTO_OR_FACE(())",
            "FINGERPRINT((ZW))",
            "PHOTO_AND_FINGERPRINT((+ZW))",
            "FACEIDCARD_OR_CARD_OR_FINGERPRINT_OR_FACE_OR_PASSWORD(ZW)",
            "MULTI_USER_TYPE()",
            "FACEIDCARD_OR_HEALTHCODE()"});
            this.cmb_OpenMethod.Location = new System.Drawing.Point(257, 68);
            this.cmb_OpenMethod.Name = "cmb_OpenMethod";
            this.cmb_OpenMethod.Size = new System.Drawing.Size(121, 22);
            this.cmb_OpenMethod.TabIndex = 32;
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(69, 71);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(182, 14);
            this.label6.TabIndex = 31;
            this.label6.Text = "DoorOpenMethod():";
            // 
            // cmb_Channel
            // 
            this.cmb_Channel.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_Channel.FormattingEnabled = true;
            this.cmb_Channel.Location = new System.Drawing.Point(257, 14);
            this.cmb_Channel.Name = "cmb_Channel";
            this.cmb_Channel.Size = new System.Drawing.Size(121, 22);
            this.cmb_Channel.TabIndex = 54;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(146, 17);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(105, 14);
            this.label1.TabIndex = 53;
            this.label1.Text = "Channel():";
            // 
            // txt_CloseTime
            // 
            this.txt_CloseTime.Location = new System.Drawing.Point(257, 338);
            this.txt_CloseTime.Name = "txt_CloseTime";
            this.txt_CloseTime.Size = new System.Drawing.Size(119, 23);
            this.txt_CloseTime.TabIndex = 58;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(48, 341);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(203, 14);
            this.label2.TabIndex = 57;
            this.label2.Text = "CloseAlwaysTime():";
            // 
            // txt_OpenTime
            // 
            this.txt_OpenTime.Location = new System.Drawing.Point(257, 284);
            this.txt_OpenTime.Name = "txt_OpenTime";
            this.txt_OpenTime.Size = new System.Drawing.Size(119, 23);
            this.txt_OpenTime.TabIndex = 56;
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(55, 287);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(196, 14);
            this.label3.TabIndex = 55;
            this.label3.Text = "OpenAlwaysTime():";
            // 
            // txt_RemoteTime
            // 
            this.txt_RemoteTime.Location = new System.Drawing.Point(257, 392);
            this.txt_RemoteTime.Name = "txt_RemoteTime";
            this.txt_RemoteTime.Size = new System.Drawing.Size(119, 23);
            this.txt_RemoteTime.TabIndex = 60;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(27, 395);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(224, 14);
            this.label4.TabIndex = 59;
            this.label4.Text = "AutoRemoteTime():";
            // 
            // chb_RemoteEnable
            // 
            this.chb_RemoteEnable.AutoSize = true;
            this.chb_RemoteEnable.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_RemoteEnable.Location = new System.Drawing.Point(382, 394);
            this.chb_RemoteEnable.Name = "chb_RemoteEnable";
            this.chb_RemoteEnable.Size = new System.Drawing.Size(110, 18);
            this.chb_RemoteEnable.TabIndex = 62;
            this.chb_RemoteEnable.Text = "Enable()";
            this.chb_RemoteEnable.UseVisualStyleBackColor = true;
            // 
            // btn_OpenTime
            // 
            this.btn_OpenTime.Enabled = false;
            this.btn_OpenTime.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_OpenTime.Location = new System.Drawing.Point(655, 174);
            this.btn_OpenTime.Name = "btn_OpenTime";
            this.btn_OpenTime.Size = new System.Drawing.Size(58, 21);
            this.btn_OpenTime.TabIndex = 64;
            this.btn_OpenTime.Text = "···";
            this.btn_OpenTime.UseVisualStyleBackColor = true;
            this.btn_OpenTime.Click += new System.EventHandler(this.btn_OpenTime_Click);
            // 
            // label5
            // 
            this.label5.AutoSize = true;
            this.label5.Location = new System.Drawing.Point(417, 177);
            this.label5.Name = "label5";
            this.label5.Size = new System.Drawing.Size(231, 14);
            this.label5.TabIndex = 63;
            this.label5.Text = "DoorOpenTimeSection():";
            // 
            // groupBox1
            // 
            this.groupBox1.Controls.Add(this.btn_Manager);
            this.groupBox1.Location = new System.Drawing.Point(797, 179);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Size = new System.Drawing.Size(263, 100);
            this.groupBox1.TabIndex = 65;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "HolidaySet()";
            // 
            // btn_Manager
            // 
            this.btn_Manager.Font = new System.Drawing.Font("", 12F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Manager.Location = new System.Drawing.Point(61, 35);
            this.btn_Manager.Name = "btn_Manager";
            this.btn_Manager.Size = new System.Drawing.Size(140, 30);
            this.btn_Manager.TabIndex = 53;
            this.btn_Manager.Text = "Manager()";
            this.btn_Manager.UseVisualStyleBackColor = true;
            this.btn_Manager.Click += new System.EventHandler(this.btn_Manager_Click);
            // 
            // groupBox2
            // 
            this.groupBox2.Controls.Add(this.btn_Config);
            this.groupBox2.Location = new System.Drawing.Point(797, 26);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Size = new System.Drawing.Size(263, 100);
            this.groupBox2.TabIndex = 66;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "TimeSchedule()";
            // 
            // btn_Config
            // 
            this.btn_Config.Font = new System.Drawing.Font("", 12F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Config.Location = new System.Drawing.Point(61, 35);
            this.btn_Config.Name = "btn_Config";
            this.btn_Config.Size = new System.Drawing.Size(140, 30);
            this.btn_Config.TabIndex = 53;
            this.btn_Config.Text = "Config()";
            this.btn_Config.UseVisualStyleBackColor = true;
            this.btn_Config.Click += new System.EventHandler(this.btn_Config_Click);
            // 
            // chb_MaliciousSwip
            // 
            this.chb_MaliciousSwip.AutoSize = true;
            this.chb_MaliciousSwip.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_MaliciousSwip.Location = new System.Drawing.Point(655, 257);
            this.chb_MaliciousSwip.Name = "chb_MaliciousSwip";
            this.chb_MaliciousSwip.Size = new System.Drawing.Size(15, 14);
            this.chb_MaliciousSwip.TabIndex = 68;
            this.chb_MaliciousSwip.UseVisualStyleBackColor = true;
            // 
            // label13
            // 
            this.label13.AutoSize = true;
            this.label13.Location = new System.Drawing.Point(473, 257);
            this.label13.Name = "label13";
            this.label13.Size = new System.Drawing.Size(175, 14);
            this.label13.TabIndex = 67;
            this.label13.Text = "MaliciousSwip():";
            // 
            // chb_CustomPwd
            // 
            this.chb_CustomPwd.AutoSize = true;
            this.chb_CustomPwd.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_CustomPwd.Location = new System.Drawing.Point(655, 297);
            this.chb_CustomPwd.Name = "chb_CustomPwd";
            this.chb_CustomPwd.Size = new System.Drawing.Size(15, 14);
            this.chb_CustomPwd.TabIndex = 70;
            this.chb_CustomPwd.UseVisualStyleBackColor = true;
            // 
            // label17
            // 
            this.label17.AutoSize = true;
            this.label17.Location = new System.Drawing.Point(452, 297);
            this.label17.Name = "label17";
            this.label17.Size = new System.Drawing.Size(196, 14);
            this.label17.TabIndex = 69;
            this.label17.Text = "CustomPassword():";
            // 
            // GeneralConfigForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(1072, 515);
            this.Controls.Add(this.chb_CustomPwd);
            this.Controls.Add(this.label17);
            this.Controls.Add(this.chb_MaliciousSwip);
            this.Controls.Add(this.label13);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Controls.Add(this.btn_OpenTime);
            this.Controls.Add(this.label5);
            this.Controls.Add(this.chb_RemoteEnable);
            this.Controls.Add(this.txt_RemoteTime);
            this.Controls.Add(this.label4);
            this.Controls.Add(this.txt_CloseTime);
            this.Controls.Add(this.label2);
            this.Controls.Add(this.txt_OpenTime);
            this.Controls.Add(this.label3);
            this.Controls.Add(this.cmb_Channel);
            this.Controls.Add(this.label1);
            this.Controls.Add(this.btn_Set);
            this.Controls.Add(this.btn_Get);
            this.Controls.Add(this.chb_Break);
            this.Controls.Add(this.label15);
            this.Controls.Add(this.chb_Sensor);
            this.Controls.Add(this.label14);
            this.Controls.Add(this.chb_Duress);
            this.Controls.Add(this.label);
            this.Controls.Add(this.chb_Unclose);
            this.Controls.Add(this.label12);
            this.Controls.Add(this.chb_Repear);
            this.Controls.Add(this.label11);
            this.Controls.Add(this.txt_HolidayTime);
            this.Controls.Add(this.label10);
            this.Controls.Add(this.txt_CloseTimeout);
            this.Controls.Add(this.label9);
            this.Controls.Add(this.txt_UnlockHold);
            this.Controls.Add(this.label8);
            this.Controls.Add(this.cmb_AccessState);
            this.Controls.Add(this.label7);
            this.Controls.Add(this.cmb_OpenMethod);
            this.Controls.Add(this.label6);
            this.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "GeneralConfigForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "GeneralConfig()";
            this.Load += new System.EventHandler(this.GeneralConfigForm_Load);
            this.groupBox1.ResumeLayout(false);
            this.groupBox2.ResumeLayout(false);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.Button btn_Set;
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.CheckBox chb_Break;
        private System.Windows.Forms.Label label15;
        private System.Windows.Forms.CheckBox chb_Sensor;
        private System.Windows.Forms.Label label14;
        private System.Windows.Forms.CheckBox chb_Duress;
        private System.Windows.Forms.Label label;
        private System.Windows.Forms.CheckBox chb_Unclose;
        private System.Windows.Forms.Label label12;
        private System.Windows.Forms.CheckBox chb_Repear;
        private System.Windows.Forms.Label label11;
        private System.Windows.Forms.TextBox txt_HolidayTime;
        private System.Windows.Forms.Label label10;
        private System.Windows.Forms.TextBox txt_CloseTimeout;
        private System.Windows.Forms.Label label9;
        private System.Windows.Forms.TextBox txt_UnlockHold;
        private System.Windows.Forms.Label label8;
        private System.Windows.Forms.ComboBox cmb_AccessState;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.ComboBox cmb_OpenMethod;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.ComboBox cmb_Channel;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.TextBox txt_CloseTime;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_OpenTime;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.TextBox txt_RemoteTime;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.CheckBox chb_RemoteEnable;
        private System.Windows.Forms.Button btn_OpenTime;
        private System.Windows.Forms.Label label5;
        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.Button btn_Manager;
        private System.Windows.Forms.Button btn_Config;
        private System.Windows.Forms.CheckBox chb_MaliciousSwip;
        private System.Windows.Forms.Label label13;
        private System.Windows.Forms.CheckBox chb_CustomPwd;
        private System.Windows.Forms.Label label17;
    }
}