namespace AccessDemo2s
{
    partial class DoorOpenTimeSectionForm
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
            this.btn_Confirm = new System.Windows.Forms.Button();
            this.dooropenmethod4 = new System.Windows.Forms.ComboBox();
            this.endtime4 = new System.Windows.Forms.DateTimePicker();
            this.starttime4 = new System.Windows.Forms.DateTimePicker();
            this.label23 = new System.Windows.Forms.Label();
            this.dooropenmethod2 = new System.Windows.Forms.ComboBox();
            this.endtime2 = new System.Windows.Forms.DateTimePicker();
            this.starttime2 = new System.Windows.Forms.DateTimePicker();
            this.label22 = new System.Windows.Forms.Label();
            this.dooropenmethod3 = new System.Windows.Forms.ComboBox();
            this.endtime3 = new System.Windows.Forms.DateTimePicker();
            this.starttime3 = new System.Windows.Forms.DateTimePicker();
            this.label21 = new System.Windows.Forms.Label();
            this.dooropenmethod1 = new System.Windows.Forms.ComboBox();
            this.label20 = new System.Windows.Forms.Label();
            this.label19 = new System.Windows.Forms.Label();
            this.label18 = new System.Windows.Forms.Label();
            this.endtime1 = new System.Windows.Forms.DateTimePicker();
            this.starttime1 = new System.Windows.Forms.DateTimePicker();
            this.label17 = new System.Windows.Forms.Label();
            this.cmb_WeekDay = new System.Windows.Forms.ComboBox();
            this.label16 = new System.Windows.Forms.Label();
            this.SuspendLayout();
            // 
            // btn_Confirm
            // 
            this.btn_Confirm.Font = new System.Drawing.Font("", 12F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Confirm.Location = new System.Drawing.Point(315, 290);
            this.btn_Confirm.Margin = new System.Windows.Forms.Padding(5);
            this.btn_Confirm.Name = "btn_Confirm";
            this.btn_Confirm.Size = new System.Drawing.Size(163, 35);
            this.btn_Confirm.TabIndex = 73;
            this.btn_Confirm.Text = "Confirm()";
            this.btn_Confirm.UseVisualStyleBackColor = true;
            this.btn_Confirm.Click += new System.EventHandler(this.btn_Confirm_Click);
            // 
            // dooropenmethod4
            // 
            this.dooropenmethod4.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.dooropenmethod4.DropDownWidth = 600;
            this.dooropenmethod4.FormattingEnabled = true;
            this.dooropenmethod4.Items.AddRange(new object[] {
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
            this.dooropenmethod4.Location = new System.Drawing.Point(499, 244);
            this.dooropenmethod4.Name = "dooropenmethod4";
            this.dooropenmethod4.Size = new System.Drawing.Size(259, 22);
            this.dooropenmethod4.TabIndex = 72;
            // 
            // endtime4
            // 
            this.endtime4.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime4.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime4.Location = new System.Drawing.Point(333, 246);
            this.endtime4.Name = "endtime4";
            this.endtime4.ShowUpDown = true;
            this.endtime4.Size = new System.Drawing.Size(118, 23);
            this.endtime4.TabIndex = 71;
            this.endtime4.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime4
            // 
            this.starttime4.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime4.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime4.Location = new System.Drawing.Point(161, 246);
            this.starttime4.Name = "starttime4";
            this.starttime4.ShowUpDown = true;
            this.starttime4.Size = new System.Drawing.Size(118, 23);
            this.starttime4.TabIndex = 70;
            this.starttime4.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label23
            // 
            this.label23.AutoSize = true;
            this.label23.Location = new System.Drawing.Point(37, 253);
            this.label23.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label23.Name = "label23";
            this.label23.Size = new System.Drawing.Size(91, 14);
            this.label23.TabIndex = 69;
            this.label23.Text = "Seg4(4):";
            this.label23.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // dooropenmethod2
            // 
            this.dooropenmethod2.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.dooropenmethod2.DropDownWidth = 600;
            this.dooropenmethod2.FormattingEnabled = true;
            this.dooropenmethod2.Items.AddRange(new object[] {
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
            this.dooropenmethod2.Location = new System.Drawing.Point(499, 163);
            this.dooropenmethod2.Name = "dooropenmethod2";
            this.dooropenmethod2.Size = new System.Drawing.Size(259, 22);
            this.dooropenmethod2.TabIndex = 68;
            // 
            // endtime2
            // 
            this.endtime2.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime2.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime2.Location = new System.Drawing.Point(333, 162);
            this.endtime2.Name = "endtime2";
            this.endtime2.ShowUpDown = true;
            this.endtime2.Size = new System.Drawing.Size(118, 23);
            this.endtime2.TabIndex = 67;
            this.endtime2.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime2
            // 
            this.starttime2.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime2.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime2.Location = new System.Drawing.Point(161, 162);
            this.starttime2.Name = "starttime2";
            this.starttime2.ShowUpDown = true;
            this.starttime2.Size = new System.Drawing.Size(118, 23);
            this.starttime2.TabIndex = 66;
            this.starttime2.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label22
            // 
            this.label22.AutoSize = true;
            this.label22.Location = new System.Drawing.Point(37, 169);
            this.label22.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label22.Name = "label22";
            this.label22.Size = new System.Drawing.Size(91, 14);
            this.label22.TabIndex = 65;
            this.label22.Text = "Seg2(2):";
            this.label22.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // dooropenmethod3
            // 
            this.dooropenmethod3.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.dooropenmethod3.DropDownWidth = 600;
            this.dooropenmethod3.FormattingEnabled = true;
            this.dooropenmethod3.Items.AddRange(new object[] {
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
            this.dooropenmethod3.Location = new System.Drawing.Point(499, 202);
            this.dooropenmethod3.Name = "dooropenmethod3";
            this.dooropenmethod3.Size = new System.Drawing.Size(259, 22);
            this.dooropenmethod3.TabIndex = 64;
            // 
            // endtime3
            // 
            this.endtime3.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime3.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime3.Location = new System.Drawing.Point(333, 204);
            this.endtime3.Name = "endtime3";
            this.endtime3.ShowUpDown = true;
            this.endtime3.Size = new System.Drawing.Size(118, 23);
            this.endtime3.TabIndex = 63;
            this.endtime3.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime3
            // 
            this.starttime3.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime3.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime3.Location = new System.Drawing.Point(161, 204);
            this.starttime3.Name = "starttime3";
            this.starttime3.ShowUpDown = true;
            this.starttime3.Size = new System.Drawing.Size(118, 23);
            this.starttime3.TabIndex = 62;
            this.starttime3.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label21
            // 
            this.label21.AutoSize = true;
            this.label21.Location = new System.Drawing.Point(37, 211);
            this.label21.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label21.Name = "label21";
            this.label21.Size = new System.Drawing.Size(91, 14);
            this.label21.TabIndex = 61;
            this.label21.Text = "Seg3(3):";
            this.label21.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // dooropenmethod1
            // 
            this.dooropenmethod1.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.dooropenmethod1.DropDownWidth = 600;
            this.dooropenmethod1.FormattingEnabled = true;
            this.dooropenmethod1.Items.AddRange(new object[] {
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
            this.dooropenmethod1.Location = new System.Drawing.Point(499, 118);
            this.dooropenmethod1.Name = "dooropenmethod1";
            this.dooropenmethod1.Size = new System.Drawing.Size(259, 22);
            this.dooropenmethod1.TabIndex = 60;
            // 
            // label20
            // 
            this.label20.AutoSize = true;
            this.label20.Location = new System.Drawing.Point(496, 73);
            this.label20.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label20.Name = "label20";
            this.label20.Size = new System.Drawing.Size(175, 14);
            this.label20.TabIndex = 59;
            this.label20.Text = "DoorOpenMethod()";
            this.label20.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label19
            // 
            this.label19.AutoSize = true;
            this.label19.Location = new System.Drawing.Point(319, 73);
            this.label19.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label19.Name = "label19";
            this.label19.Size = new System.Drawing.Size(126, 14);
            this.label19.TabIndex = 58;
            this.label19.Text = "EndTime()";
            this.label19.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label18
            // 
            this.label18.AutoSize = true;
            this.label18.Location = new System.Drawing.Point(139, 73);
            this.label18.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label18.Name = "label18";
            this.label18.Size = new System.Drawing.Size(140, 14);
            this.label18.TabIndex = 57;
            this.label18.Text = "StartTime()";
            this.label18.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // endtime1
            // 
            this.endtime1.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime1.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime1.Location = new System.Drawing.Point(333, 120);
            this.endtime1.Name = "endtime1";
            this.endtime1.ShowUpDown = true;
            this.endtime1.Size = new System.Drawing.Size(118, 23);
            this.endtime1.TabIndex = 56;
            this.endtime1.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime1
            // 
            this.starttime1.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime1.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime1.Location = new System.Drawing.Point(161, 120);
            this.starttime1.Name = "starttime1";
            this.starttime1.ShowUpDown = true;
            this.starttime1.Size = new System.Drawing.Size(118, 23);
            this.starttime1.TabIndex = 55;
            this.starttime1.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label17
            // 
            this.label17.AutoSize = true;
            this.label17.Location = new System.Drawing.Point(37, 127);
            this.label17.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label17.Name = "label17";
            this.label17.Size = new System.Drawing.Size(91, 14);
            this.label17.TabIndex = 54;
            this.label17.Text = "Seg1(1):";
            this.label17.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // cmb_WeekDay
            // 
            this.cmb_WeekDay.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_WeekDay.FormattingEnabled = true;
            this.cmb_WeekDay.Items.AddRange(new object[] {
            "Sun.()",
            "Mon.() ",
            "Tue.() ",
            "Wed.() ",
            "Thu.() ",
            "Fri.() ",
            "Sat.() "});
            this.cmb_WeekDay.Location = new System.Drawing.Point(304, 27);
            this.cmb_WeekDay.Name = "cmb_WeekDay";
            this.cmb_WeekDay.Size = new System.Drawing.Size(173, 22);
            this.cmb_WeekDay.TabIndex = 53;
            this.cmb_WeekDay.SelectedIndexChanged += new System.EventHandler(this.cmb_WeekDay_SelectedIndexChanged);
            // 
            // label16
            // 
            this.label16.AutoSize = true;
            this.label16.Location = new System.Drawing.Point(174, 30);
            this.label16.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label16.Name = "label16";
            this.label16.Size = new System.Drawing.Size(105, 14);
            this.label16.TabIndex = 52;
            this.label16.Text = "WeekDay():";
            this.label16.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // DoorOpenTimeSectionForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(790, 353);
            this.Controls.Add(this.btn_Confirm);
            this.Controls.Add(this.dooropenmethod4);
            this.Controls.Add(this.endtime4);
            this.Controls.Add(this.starttime4);
            this.Controls.Add(this.label23);
            this.Controls.Add(this.dooropenmethod2);
            this.Controls.Add(this.endtime2);
            this.Controls.Add(this.starttime2);
            this.Controls.Add(this.label22);
            this.Controls.Add(this.dooropenmethod3);
            this.Controls.Add(this.endtime3);
            this.Controls.Add(this.starttime3);
            this.Controls.Add(this.label21);
            this.Controls.Add(this.dooropenmethod1);
            this.Controls.Add(this.label20);
            this.Controls.Add(this.label19);
            this.Controls.Add(this.label18);
            this.Controls.Add(this.endtime1);
            this.Controls.Add(this.starttime1);
            this.Controls.Add(this.label17);
            this.Controls.Add(this.cmb_WeekDay);
            this.Controls.Add(this.label16);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "DoorOpenTimeSectionForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "DoorOpenTimeSection()";
            this.Load += new System.EventHandler(this.DoorOpenTimeSectionForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.Button btn_Confirm;
        private System.Windows.Forms.ComboBox dooropenmethod4;
        private System.Windows.Forms.DateTimePicker endtime4;
        private System.Windows.Forms.DateTimePicker starttime4;
        private System.Windows.Forms.Label label23;
        private System.Windows.Forms.ComboBox dooropenmethod2;
        private System.Windows.Forms.DateTimePicker endtime2;
        private System.Windows.Forms.DateTimePicker starttime2;
        private System.Windows.Forms.Label label22;
        private System.Windows.Forms.ComboBox dooropenmethod3;
        private System.Windows.Forms.DateTimePicker endtime3;
        private System.Windows.Forms.DateTimePicker starttime3;
        private System.Windows.Forms.Label label21;
        private System.Windows.Forms.ComboBox dooropenmethod1;
        private System.Windows.Forms.Label label20;
        private System.Windows.Forms.Label label19;
        private System.Windows.Forms.Label label18;
        private System.Windows.Forms.DateTimePicker endtime1;
        private System.Windows.Forms.DateTimePicker starttime1;
        private System.Windows.Forms.Label label17;
        private System.Windows.Forms.ComboBox cmb_WeekDay;
        private System.Windows.Forms.Label label16;
    }
}