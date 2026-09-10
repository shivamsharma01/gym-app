namespace AccessDemo2s
{
    partial class RecordQueryForm
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
            this.tabControl1 = new System.Windows.Forms.TabControl();
            this.tabPage1 = new System.Windows.Forms.TabPage();
            this.listView_DoorRecord = new System.Windows.Forms.ListView();
            this.columnHeader2 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader3 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader4 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader5 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader1 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader7 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.txt_DoorRecordCount = new System.Windows.Forms.TextBox();
            this.btn_GetDoorRecordCount = new System.Windows.Forms.Button();
            this.btn_NextDoorRecord = new System.Windows.Forms.Button();
            this.btn_StartQueryDoorRecord = new System.Windows.Forms.Button();
            this.dateTimePicker_DoorEnd = new System.Windows.Forms.DateTimePicker();
            this.dateTimePicker_DoorStart = new System.Windows.Forms.DateTimePicker();
            this.label9 = new System.Windows.Forms.Label();
            this.label10 = new System.Windows.Forms.Label();
            this.tabPage2 = new System.Windows.Forms.TabPage();
            this.txt_AlarmRecordCount = new System.Windows.Forms.TextBox();
            this.btn_GetAlarmRecordCount = new System.Windows.Forms.Button();
            this.btn_NextAlarmRecord = new System.Windows.Forms.Button();
            this.btn_StartQueryAlarmRecord = new System.Windows.Forms.Button();
            this.dateTimePicker_AlarmEnd = new System.Windows.Forms.DateTimePicker();
            this.dateTimePicker_AlarmStart = new System.Windows.Forms.DateTimePicker();
            this.label1 = new System.Windows.Forms.Label();
            this.label2 = new System.Windows.Forms.Label();
            this.listView_AlarmRecord = new System.Windows.Forms.ListView();
            this.columnHeader6 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader8 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader9 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader10 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader11 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader12 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.tabPage3 = new System.Windows.Forms.TabPage();
            this.txt_LogCount = new System.Windows.Forms.TextBox();
            this.btn_GetLogCount = new System.Windows.Forms.Button();
            this.btn_NextLog = new System.Windows.Forms.Button();
            this.btn_StartQueryLog = new System.Windows.Forms.Button();
            this.listView_Log = new System.Windows.Forms.ListView();
            this.columnHeader13 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader14 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader15 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader16 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader17 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.tabControl1.SuspendLayout();
            this.tabPage1.SuspendLayout();
            this.tabPage2.SuspendLayout();
            this.tabPage3.SuspendLayout();
            this.SuspendLayout();
            // 
            // tabControl1
            // 
            this.tabControl1.Controls.Add(this.tabPage1);
            this.tabControl1.Controls.Add(this.tabPage2);
            this.tabControl1.Controls.Add(this.tabPage3);
            this.tabControl1.ItemSize = new System.Drawing.Size(180, 20);
            this.tabControl1.Location = new System.Drawing.Point(14, 14);
            this.tabControl1.Name = "tabControl1";
            this.tabControl1.SelectedIndex = 0;
            this.tabControl1.Size = new System.Drawing.Size(845, 623);
            this.tabControl1.SizeMode = System.Windows.Forms.TabSizeMode.Fixed;
            this.tabControl1.TabIndex = 0;
            // 
            // tabPage1
            // 
            this.tabPage1.Controls.Add(this.listView_DoorRecord);
            this.tabPage1.Controls.Add(this.txt_DoorRecordCount);
            this.tabPage1.Controls.Add(this.btn_GetDoorRecordCount);
            this.tabPage1.Controls.Add(this.btn_NextDoorRecord);
            this.tabPage1.Controls.Add(this.btn_StartQueryDoorRecord);
            this.tabPage1.Controls.Add(this.dateTimePicker_DoorEnd);
            this.tabPage1.Controls.Add(this.dateTimePicker_DoorStart);
            this.tabPage1.Controls.Add(this.label9);
            this.tabPage1.Controls.Add(this.label10);
            this.tabPage1.Location = new System.Drawing.Point(4, 24);
            this.tabPage1.Name = "tabPage1";
            this.tabPage1.Padding = new System.Windows.Forms.Padding(3);
            this.tabPage1.Size = new System.Drawing.Size(837, 595);
            this.tabPage1.TabIndex = 0;
            this.tabPage1.Text = "DoorRecord()";
            this.tabPage1.UseVisualStyleBackColor = true;
            // 
            // listView_DoorRecord
            // 
            this.listView_DoorRecord.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader2,
            this.columnHeader3,
            this.columnHeader4,
            this.columnHeader5,
            this.columnHeader1,
            this.columnHeader7});
            this.listView_DoorRecord.FullRowSelect = true;
            this.listView_DoorRecord.GridLines = true;
            this.listView_DoorRecord.Location = new System.Drawing.Point(6, 175);
            this.listView_DoorRecord.Name = "listView_DoorRecord";
            this.listView_DoorRecord.Size = new System.Drawing.Size(825, 412);
            this.listView_DoorRecord.TabIndex = 47;
            this.listView_DoorRecord.UseCompatibleStateImageBehavior = false;
            this.listView_DoorRecord.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader2
            // 
            this.columnHeader2.Text = "No.()";
            this.columnHeader2.Width = 75;
            // 
            // columnHeader3
            // 
            this.columnHeader3.Text = "Time()";
            this.columnHeader3.Width = 160;
            // 
            // columnHeader4
            // 
            this.columnHeader4.Text = "CardNo.()";
            this.columnHeader4.Width = 150;
            // 
            // columnHeader5
            // 
            this.columnHeader5.Text = "State()";
            this.columnHeader5.Width = 140;
            // 
            // columnHeader1
            // 
            this.columnHeader1.Text = "Door()";
            this.columnHeader1.Width = 100;
            // 
            // columnHeader7
            // 
            this.columnHeader7.Text = "Type()";
            this.columnHeader7.Width = 140;
            // 
            // txt_DoorRecordCount
            // 
            this.txt_DoorRecordCount.Location = new System.Drawing.Point(434, 82);
            this.txt_DoorRecordCount.Margin = new System.Windows.Forms.Padding(4);
            this.txt_DoorRecordCount.Name = "txt_DoorRecordCount";
            this.txt_DoorRecordCount.ReadOnly = true;
            this.txt_DoorRecordCount.Size = new System.Drawing.Size(80, 23);
            this.txt_DoorRecordCount.TabIndex = 46;
            // 
            // btn_GetDoorRecordCount
            // 
            this.btn_GetDoorRecordCount.Enabled = false;
            this.btn_GetDoorRecordCount.Location = new System.Drawing.Point(208, 78);
            this.btn_GetDoorRecordCount.Name = "btn_GetDoorRecordCount";
            this.btn_GetDoorRecordCount.Size = new System.Drawing.Size(219, 27);
            this.btn_GetDoorRecordCount.TabIndex = 45;
            this.btn_GetDoorRecordCount.Text = "GetRecordCount()";
            this.btn_GetDoorRecordCount.UseVisualStyleBackColor = true;
            this.btn_GetDoorRecordCount.Click += new System.EventHandler(this.btn_GetDoorRecordCount_Click);
            // 
            // btn_NextDoorRecord
            // 
            this.btn_NextDoorRecord.Enabled = false;
            this.btn_NextDoorRecord.Location = new System.Drawing.Point(26, 123);
            this.btn_NextDoorRecord.Name = "btn_NextDoorRecord";
            this.btn_NextDoorRecord.Size = new System.Drawing.Size(160, 27);
            this.btn_NextDoorRecord.TabIndex = 43;
            this.btn_NextDoorRecord.Text = "NextPage()";
            this.btn_NextDoorRecord.UseVisualStyleBackColor = true;
            this.btn_NextDoorRecord.Click += new System.EventHandler(this.btn_NextDoorRecord_Click);
            // 
            // btn_StartQueryDoorRecord
            // 
            this.btn_StartQueryDoorRecord.Location = new System.Drawing.Point(26, 79);
            this.btn_StartQueryDoorRecord.Name = "btn_StartQueryDoorRecord";
            this.btn_StartQueryDoorRecord.Size = new System.Drawing.Size(160, 27);
            this.btn_StartQueryDoorRecord.TabIndex = 42;
            this.btn_StartQueryDoorRecord.Text = "StartQuery()";
            this.btn_StartQueryDoorRecord.UseVisualStyleBackColor = true;
            this.btn_StartQueryDoorRecord.Click += new System.EventHandler(this.btn_StartQueryDoorRecord_Click);
            // 
            // dateTimePicker_DoorEnd
            // 
            this.dateTimePicker_DoorEnd.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.dateTimePicker_DoorEnd.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.dateTimePicker_DoorEnd.Location = new System.Drawing.Point(177, 41);
            this.dateTimePicker_DoorEnd.Name = "dateTimePicker_DoorEnd";
            this.dateTimePicker_DoorEnd.Size = new System.Drawing.Size(175, 23);
            this.dateTimePicker_DoorEnd.TabIndex = 41;
            // 
            // dateTimePicker_DoorStart
            // 
            this.dateTimePicker_DoorStart.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.dateTimePicker_DoorStart.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.dateTimePicker_DoorStart.Location = new System.Drawing.Point(177, 12);
            this.dateTimePicker_DoorStart.Name = "dateTimePicker_DoorStart";
            this.dateTimePicker_DoorStart.Size = new System.Drawing.Size(175, 23);
            this.dateTimePicker_DoorStart.TabIndex = 40;
            // 
            // label9
            // 
            this.label9.AutoSize = true;
            this.label9.Location = new System.Drawing.Point(37, 47);
            this.label9.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label9.Name = "label9";
            this.label9.Size = new System.Drawing.Size(133, 14);
            this.label9.TabIndex = 39;
            this.label9.Text = "EndTime():";
            // 
            // label10
            // 
            this.label10.AutoSize = true;
            this.label10.Location = new System.Drawing.Point(23, 18);
            this.label10.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label10.Name = "label10";
            this.label10.Size = new System.Drawing.Size(147, 14);
            this.label10.TabIndex = 38;
            this.label10.Text = "StartTime():";
            // 
            // tabPage2
            // 
            this.tabPage2.Controls.Add(this.txt_AlarmRecordCount);
            this.tabPage2.Controls.Add(this.btn_GetAlarmRecordCount);
            this.tabPage2.Controls.Add(this.btn_NextAlarmRecord);
            this.tabPage2.Controls.Add(this.btn_StartQueryAlarmRecord);
            this.tabPage2.Controls.Add(this.dateTimePicker_AlarmEnd);
            this.tabPage2.Controls.Add(this.dateTimePicker_AlarmStart);
            this.tabPage2.Controls.Add(this.label1);
            this.tabPage2.Controls.Add(this.label2);
            this.tabPage2.Controls.Add(this.listView_AlarmRecord);
            this.tabPage2.Location = new System.Drawing.Point(4, 24);
            this.tabPage2.Name = "tabPage2";
            this.tabPage2.Padding = new System.Windows.Forms.Padding(3);
            this.tabPage2.Size = new System.Drawing.Size(837, 595);
            this.tabPage2.TabIndex = 1;
            this.tabPage2.Text = "AlarmRecord()";
            this.tabPage2.UseVisualStyleBackColor = true;
            // 
            // txt_AlarmRecordCount
            // 
            this.txt_AlarmRecordCount.Location = new System.Drawing.Point(434, 82);
            this.txt_AlarmRecordCount.Margin = new System.Windows.Forms.Padding(4);
            this.txt_AlarmRecordCount.Name = "txt_AlarmRecordCount";
            this.txt_AlarmRecordCount.ReadOnly = true;
            this.txt_AlarmRecordCount.Size = new System.Drawing.Size(80, 23);
            this.txt_AlarmRecordCount.TabIndex = 65;
            // 
            // btn_GetAlarmRecordCount
            // 
            this.btn_GetAlarmRecordCount.Enabled = false;
            this.btn_GetAlarmRecordCount.Location = new System.Drawing.Point(208, 78);
            this.btn_GetAlarmRecordCount.Name = "btn_GetAlarmRecordCount";
            this.btn_GetAlarmRecordCount.Size = new System.Drawing.Size(219, 27);
            this.btn_GetAlarmRecordCount.TabIndex = 64;
            this.btn_GetAlarmRecordCount.Text = "GetRecordCount()";
            this.btn_GetAlarmRecordCount.UseVisualStyleBackColor = true;
            this.btn_GetAlarmRecordCount.Click += new System.EventHandler(this.btn_GetAlarmRecordCount_Click);
            // 
            // btn_NextAlarmRecord
            // 
            this.btn_NextAlarmRecord.Enabled = false;
            this.btn_NextAlarmRecord.Location = new System.Drawing.Point(26, 123);
            this.btn_NextAlarmRecord.Name = "btn_NextAlarmRecord";
            this.btn_NextAlarmRecord.Size = new System.Drawing.Size(160, 27);
            this.btn_NextAlarmRecord.TabIndex = 63;
            this.btn_NextAlarmRecord.Text = "NextPage()";
            this.btn_NextAlarmRecord.UseVisualStyleBackColor = true;
            this.btn_NextAlarmRecord.Click += new System.EventHandler(this.btn_NextAlarmRecord_Click);
            // 
            // btn_StartQueryAlarmRecord
            // 
            this.btn_StartQueryAlarmRecord.Location = new System.Drawing.Point(26, 79);
            this.btn_StartQueryAlarmRecord.Name = "btn_StartQueryAlarmRecord";
            this.btn_StartQueryAlarmRecord.Size = new System.Drawing.Size(160, 27);
            this.btn_StartQueryAlarmRecord.TabIndex = 62;
            this.btn_StartQueryAlarmRecord.Text = "StartQuery()";
            this.btn_StartQueryAlarmRecord.UseVisualStyleBackColor = true;
            this.btn_StartQueryAlarmRecord.Click += new System.EventHandler(this.btn_StartQueryAlarmRecord_Click);
            // 
            // dateTimePicker_AlarmEnd
            // 
            this.dateTimePicker_AlarmEnd.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.dateTimePicker_AlarmEnd.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.dateTimePicker_AlarmEnd.Location = new System.Drawing.Point(177, 41);
            this.dateTimePicker_AlarmEnd.Name = "dateTimePicker_AlarmEnd";
            this.dateTimePicker_AlarmEnd.Size = new System.Drawing.Size(175, 23);
            this.dateTimePicker_AlarmEnd.TabIndex = 61;
            // 
            // dateTimePicker_AlarmStart
            // 
            this.dateTimePicker_AlarmStart.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.dateTimePicker_AlarmStart.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.dateTimePicker_AlarmStart.Location = new System.Drawing.Point(177, 12);
            this.dateTimePicker_AlarmStart.Name = "dateTimePicker_AlarmStart";
            this.dateTimePicker_AlarmStart.Size = new System.Drawing.Size(175, 23);
            this.dateTimePicker_AlarmStart.TabIndex = 60;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(37, 47);
            this.label1.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(133, 14);
            this.label1.TabIndex = 59;
            this.label1.Text = "EndTime():";
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(23, 18);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(147, 14);
            this.label2.TabIndex = 58;
            this.label2.Text = "StartTime():";
            // 
            // listView_AlarmRecord
            // 
            this.listView_AlarmRecord.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader6,
            this.columnHeader8,
            this.columnHeader9,
            this.columnHeader10,
            this.columnHeader11,
            this.columnHeader12});
            this.listView_AlarmRecord.FullRowSelect = true;
            this.listView_AlarmRecord.GridLines = true;
            this.listView_AlarmRecord.Location = new System.Drawing.Point(6, 175);
            this.listView_AlarmRecord.Name = "listView_AlarmRecord";
            this.listView_AlarmRecord.Size = new System.Drawing.Size(825, 412);
            this.listView_AlarmRecord.TabIndex = 57;
            this.listView_AlarmRecord.UseCompatibleStateImageBehavior = false;
            this.listView_AlarmRecord.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader6
            // 
            this.columnHeader6.Text = "No.()";
            this.columnHeader6.Width = 75;
            // 
            // columnHeader8
            // 
            this.columnHeader8.Text = "Time()";
            this.columnHeader8.Width = 160;
            // 
            // columnHeader9
            // 
            this.columnHeader9.Text = "UserNo.()";
            this.columnHeader9.Width = 140;
            // 
            // columnHeader10
            // 
            this.columnHeader10.Text = "AlarmType()";
            this.columnHeader10.Width = 150;
            // 
            // columnHeader11
            // 
            this.columnHeader11.Text = "Device()";
            this.columnHeader11.Width = 140;
            // 
            // columnHeader12
            // 
            this.columnHeader12.Text = "Channel()";
            this.columnHeader12.Width = 120;
            // 
            // tabPage3
            // 
            this.tabPage3.Controls.Add(this.txt_LogCount);
            this.tabPage3.Controls.Add(this.btn_GetLogCount);
            this.tabPage3.Controls.Add(this.btn_NextLog);
            this.tabPage3.Controls.Add(this.btn_StartQueryLog);
            this.tabPage3.Controls.Add(this.listView_Log);
            this.tabPage3.Location = new System.Drawing.Point(4, 24);
            this.tabPage3.Name = "tabPage3";
            this.tabPage3.Padding = new System.Windows.Forms.Padding(3);
            this.tabPage3.Size = new System.Drawing.Size(837, 595);
            this.tabPage3.TabIndex = 2;
            this.tabPage3.Text = "Log()";
            this.tabPage3.UseVisualStyleBackColor = true;
            // 
            // txt_LogCount
            // 
            this.txt_LogCount.Location = new System.Drawing.Point(423, 18);
            this.txt_LogCount.Margin = new System.Windows.Forms.Padding(4);
            this.txt_LogCount.Name = "txt_LogCount";
            this.txt_LogCount.ReadOnly = true;
            this.txt_LogCount.Size = new System.Drawing.Size(80, 23);
            this.txt_LogCount.TabIndex = 69;
            // 
            // btn_GetLogCount
            // 
            this.btn_GetLogCount.Enabled = false;
            this.btn_GetLogCount.Location = new System.Drawing.Point(216, 15);
            this.btn_GetLogCount.Name = "btn_GetLogCount";
            this.btn_GetLogCount.Size = new System.Drawing.Size(200, 27);
            this.btn_GetLogCount.TabIndex = 68;
            this.btn_GetLogCount.Text = "GetLogCount()";
            this.btn_GetLogCount.UseVisualStyleBackColor = true;
            this.btn_GetLogCount.Click += new System.EventHandler(this.btn_GetLogCount_Click);
            // 
            // btn_NextLog
            // 
            this.btn_NextLog.Enabled = false;
            this.btn_NextLog.Location = new System.Drawing.Point(26, 59);
            this.btn_NextLog.Name = "btn_NextLog";
            this.btn_NextLog.Size = new System.Drawing.Size(160, 27);
            this.btn_NextLog.TabIndex = 67;
            this.btn_NextLog.Text = "NextPage()";
            this.btn_NextLog.UseVisualStyleBackColor = true;
            this.btn_NextLog.Click += new System.EventHandler(this.btn_NextLog_Click);
            // 
            // btn_StartQueryLog
            // 
            this.btn_StartQueryLog.Location = new System.Drawing.Point(26, 15);
            this.btn_StartQueryLog.Name = "btn_StartQueryLog";
            this.btn_StartQueryLog.Size = new System.Drawing.Size(160, 27);
            this.btn_StartQueryLog.TabIndex = 66;
            this.btn_StartQueryLog.Text = "StartQuery()";
            this.btn_StartQueryLog.UseVisualStyleBackColor = true;
            this.btn_StartQueryLog.Click += new System.EventHandler(this.btn_StartQueryLog_Click);
            // 
            // listView_Log
            // 
            this.listView_Log.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader13,
            this.columnHeader14,
            this.columnHeader15,
            this.columnHeader16,
            this.columnHeader17});
            this.listView_Log.FullRowSelect = true;
            this.listView_Log.GridLines = true;
            this.listView_Log.Location = new System.Drawing.Point(6, 111);
            this.listView_Log.Name = "listView_Log";
            this.listView_Log.Size = new System.Drawing.Size(825, 476);
            this.listView_Log.TabIndex = 62;
            this.listView_Log.UseCompatibleStateImageBehavior = false;
            this.listView_Log.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader13
            // 
            this.columnHeader13.Text = "No.()";
            this.columnHeader13.Width = 75;
            // 
            // columnHeader14
            // 
            this.columnHeader14.Text = "Time()";
            this.columnHeader14.Width = 160;
            // 
            // columnHeader15
            // 
            this.columnHeader15.Text = "UserName()";
            this.columnHeader15.Width = 140;
            // 
            // columnHeader16
            // 
            this.columnHeader16.Text = "Type()";
            this.columnHeader16.Width = 150;
            // 
            // columnHeader17
            // 
            this.columnHeader17.Text = "Content()";
            this.columnHeader17.Width = 140;
            // 
            // RecordQueryForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(871, 650);
            this.Controls.Add(this.tabControl1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "RecordQueryForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "QueryRecord()";
            this.tabControl1.ResumeLayout(false);
            this.tabPage1.ResumeLayout(false);
            this.tabPage1.PerformLayout();
            this.tabPage2.ResumeLayout(false);
            this.tabPage2.PerformLayout();
            this.tabPage3.ResumeLayout(false);
            this.tabPage3.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.TabControl tabControl1;
        private System.Windows.Forms.TabPage tabPage1;
        private System.Windows.Forms.TabPage tabPage2;
        private System.Windows.Forms.TabPage tabPage3;
        private System.Windows.Forms.Button btn_NextDoorRecord;
        private System.Windows.Forms.Button btn_StartQueryDoorRecord;
        private System.Windows.Forms.DateTimePicker dateTimePicker_DoorEnd;
        private System.Windows.Forms.DateTimePicker dateTimePicker_DoorStart;
        private System.Windows.Forms.Label label9;
        private System.Windows.Forms.Label label10;
        private System.Windows.Forms.Button btn_GetDoorRecordCount;
        private System.Windows.Forms.TextBox txt_DoorRecordCount;
        private System.Windows.Forms.ListView listView_DoorRecord;
        private System.Windows.Forms.ColumnHeader columnHeader2;
        private System.Windows.Forms.ColumnHeader columnHeader3;
        private System.Windows.Forms.ColumnHeader columnHeader4;
        private System.Windows.Forms.ColumnHeader columnHeader5;
        private System.Windows.Forms.ColumnHeader columnHeader1;
        private System.Windows.Forms.ColumnHeader columnHeader7;
        private System.Windows.Forms.ListView listView_AlarmRecord;
        private System.Windows.Forms.ColumnHeader columnHeader6;
        private System.Windows.Forms.ColumnHeader columnHeader8;
        private System.Windows.Forms.ColumnHeader columnHeader9;
        private System.Windows.Forms.ColumnHeader columnHeader10;
        private System.Windows.Forms.ColumnHeader columnHeader11;
        private System.Windows.Forms.ColumnHeader columnHeader12;
        private System.Windows.Forms.ListView listView_Log;
        private System.Windows.Forms.ColumnHeader columnHeader13;
        private System.Windows.Forms.ColumnHeader columnHeader14;
        private System.Windows.Forms.ColumnHeader columnHeader15;
        private System.Windows.Forms.ColumnHeader columnHeader16;
        private System.Windows.Forms.ColumnHeader columnHeader17;
        private System.Windows.Forms.TextBox txt_AlarmRecordCount;
        private System.Windows.Forms.Button btn_GetAlarmRecordCount;
        private System.Windows.Forms.Button btn_NextAlarmRecord;
        private System.Windows.Forms.Button btn_StartQueryAlarmRecord;
        private System.Windows.Forms.DateTimePicker dateTimePicker_AlarmEnd;
        private System.Windows.Forms.DateTimePicker dateTimePicker_AlarmStart;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_LogCount;
        private System.Windows.Forms.Button btn_GetLogCount;
        private System.Windows.Forms.Button btn_NextLog;
        private System.Windows.Forms.Button btn_StartQueryLog;
    }
}